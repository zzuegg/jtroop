package jtroop.server;

import jtroop.codec.CodecRegistry;
import jtroop.core.EventLoop;
import jtroop.core.EventLoopGroup;
import jtroop.core.Handshake;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Layer;
import jtroop.pipeline.Pipeline;
import jtroop.service.ServiceRegistry;
import jtroop.session.ConnectionId;
import jtroop.session.SessionStore;
import jtroop.transport.Transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Server implements AutoCloseable {

    private record ListenerConfig(
            Class<? extends Record> connectionType,
            Transport transport,
            Pipeline pipeline,
            Layer[] layers
    ) {}

    private record ServiceBinding(
            Object handlerInstance,
            Class<? extends Record> connectionType
    ) {}

    private final List<ListenerConfig> listeners;
    private final ServiceRegistry serviceRegistry;
    private final CodecRegistry codec;
    private final SessionStore sessions;
    private final EventLoop acceptLoop;
    private final EventLoopGroup workerGroup;
    // Per-thread scratch buffers for encoding. sendResponse may run on any
    // worker loop (broadcast fan-out) or on an executor thread (request/response).
    // Sharing a single ByteBuffer across threads would corrupt encoded frames.
    private final ThreadLocal<ByteBuffer> serverEncodeBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(65536));
    private final ThreadLocal<ByteBuffer> serverWireBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(65536));
    // All connection-state maps are mutated on worker/accept loops and read
    // from any loop during broadcast/unicast fan-out. ConcurrentHashMap for safety.
    private final Map<Class<? extends Record>, Integer> boundPorts = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, Integer> boundUdpPorts = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, java.nio.channels.DatagramChannel> udpChannels = new ConcurrentHashMap<>();
    private final Map<SelectionKey, ConnectionId> keyToConnection = new ConcurrentHashMap<>();
    private final Map<ConnectionId, SelectionKey> connectionToKey = new ConcurrentHashMap<>();
    private final Map<ConnectionId, SocketChannel> connectionChannels = new ConcurrentHashMap<>();
    private final Map<ConnectionId, ListenerConfig> connectionConfig = new ConcurrentHashMap<>();
    @SuppressWarnings("rawtypes")
    private final Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers;
    private final Set<ConnectionId> handshakePending = ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.Executor executor;

    @SuppressWarnings("rawtypes")
    private Server(List<ListenerConfig> listeners, ServiceRegistry serviceRegistry,
                   CodecRegistry codec,
                   Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers,
                   java.util.concurrent.Executor executor, int workerCount) {
        this.listeners = listeners;
        this.serviceRegistry = serviceRegistry;
        this.codec = codec;
        this.handshakeHandlers = handshakeHandlers;
        this.executor = executor != null ? executor : Runnable::run;
        this.sessions = new SessionStore(4096);
        try {
            this.acceptLoop = new EventLoop("server-accept");
            this.workerGroup = new EventLoopGroup(workerCount);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create event loops", e);
        }
        // Wire broadcast and unicast
        serviceRegistry.setBroadcast(this::broadcastImpl);
        serviceRegistry.setUnicast(this::unicastImpl);
    }

    private void broadcastImpl(Record message) {
        sessions.forEachActive(connId -> {
            var channel = connectionChannels.get(connId);
            var config = connectionConfig.get(connId);
            if (channel != null && channel.isConnected() && config != null) {
                sendResponse(message, config, channel);
            }
        });
    }

    private void unicastImpl(ConnectionId target, Record message) {
        var channel = connectionChannels.get(target);
        var config = connectionConfig.get(target);
        if (channel != null && channel.isConnected() && config != null) {
            sendResponse(message, config, channel);
        }
    }

    public void start() throws IOException {
        acceptLoop.start();
        workerGroup.start();
        for (var listener : listeners) {
            if (listener.transport().isTcp()) {
                startTcpListener(listener);
            } else if (listener.transport().isUdp()) {
                startUdpListener(listener);
            }
        }
    }

    private void startTcpListener(ListenerConfig config) throws IOException {
        var serverChannel = ServerSocketChannel.open();
        serverChannel.bind(config.transport().address());
        int port = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        boundPorts.put(config.connectionType(), port);

        acceptLoop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(acceptLoop.selector(), SelectionKey.OP_ACCEPT,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isAcceptable()) {
                                acceptClient(serverChannel, config);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to register server socket", e);
            }
        });
    }

    private void startUdpListener(ListenerConfig config) throws IOException {
        var channel = java.nio.channels.DatagramChannel.open();
        channel.bind(config.transport().address());
        int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        boundUdpPorts.put(config.connectionType(), port);
        udpChannels.put(config.connectionType(), channel);

        var udpLoop = workerGroup.next();
        udpLoop.submit(() -> {
            try {
                channel.configureBlocking(false);
                var readBuf = ByteBuffer.allocate(65536);
                channel.register(udpLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isReadable()) {
                                handleUdpRead(channel, config, readBuf);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("Failed to register UDP socket", e);
            }
        });
    }

    private void handleUdpRead(java.nio.channels.DatagramChannel channel, ListenerConfig config,
                                ByteBuffer readBuf) throws IOException {
        readBuf.clear();
        // {@link DatagramChannel#receive} returns a fresh {@link
        // InetSocketAddress} per packet for unconnected channels (~32 B/op).
        // This is the server's bottleneck for zero-alloc UDP — accepting it
        // for now since multi-peer semantics require knowing the source.
        var remoteAddr = channel.receive(readBuf);
        if (remoteAddr == null) return;
        readBuf.flip();

        // For UDP, use a synthetic ConnectionId based on remote address hash
        // (simplified — real impl would track UDP sessions)
        var connId = ConnectionId.of(remoteAddr.hashCode() & 0x7FFFFFFF % 4096, 1);

        // Decode message directly (no framing needed for UDP — datagrams are self-delimiting)
        if (readBuf.remaining() >= 2) {
            var rb = new ReadBuffer(readBuf);
            var message = codec.decode(rb);
            serviceRegistry.dispatch(message, connId);
        }
    }

    private void acceptClient(ServerSocketChannel serverChannel, ListenerConfig config) throws IOException {
        var clientChannel = serverChannel.accept();
        if (clientChannel == null) return;
        clientChannel.configureBlocking(false);
        var connId = sessions.allocate();
        var readBuf = ByteBuffer.allocate(65536);

        boolean needsHandshake = handshakeHandlers.containsKey(config.connectionType());
        if (needsHandshake) {
            handshakePending.add(connId);
        }

        connectionChannels.put(connId, clientChannel);
        connectionConfig.put(connId, config);

        // Assign to a worker loop (round-robin)
        var workerLoop = workerGroup.next();
        workerLoop.submit(() -> {
            try {
                var selKey = clientChannel.register(workerLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> handleRead(key, connId, config, readBuf));
                keyToConnection.put(selKey, connId);
                connectionToKey.put(connId, selKey);
            } catch (IOException e) {
                throw new RuntimeException("Failed to register client channel", e);
            }
        });

        if (!needsHandshake) {
            serviceRegistry.dispatchConnect(connId);
        }
    }

    private void handleRead(SelectionKey key, ConnectionId connId, ListenerConfig config,
                            ByteBuffer readBuf) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n;
        try {
            n = channel.read(readBuf);
        } catch (IOException e) {
            // Abrupt close / reset — treat as disconnect
            n = -1;
        }
        if (n == -1) {
            closeConnectionInternal(connId, key, channel);
            return;
        }
        if (n > 0) {
            readBuf.flip();
            try {
                if (handshakePending.contains(connId)) {
                    processHandshake(readBuf, connId, config, channel, key);
                } else {
                    processInbound(readBuf, connId, config, channel);
                }
            } catch (Throwable t) {
                // Malformed / malicious input: framing length out of range,
                // unknown message type, truncated record, bad HTTP, etc.
                // Close this connection cleanly — fires @OnDisconnect if the
                // handshake had already completed — but do NOT let the
                // exception reach the EventLoop, since that would cancel the
                // key for the wrong reason and still leave state inconsistent.
                System.err.println("Server: malformed input on " + connId + ", closing: " + t);
                if (handshakePending.contains(connId)) {
                    // Never dispatched connect → don't dispatch disconnect.
                    rejectConnection(channel, config, key, connId);
                } else {
                    closeConnectionInternal(connId, key, channel);
                }
                return;
            }
            readBuf.compact();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processHandshake(ByteBuffer wire, ConnectionId connId, ListenerConfig config,
                                   SocketChannel channel, SelectionKey key) {
        var frame = config.pipeline().decodeInbound(wire);
        if (frame == null) return;

        var rb = new ReadBuffer(frame);
        int magic = rb.readInt();
        if (magic != Handshake.MAGIC) {
            // Not a handshake frame, reject
            rejectConnection(channel, config, key, connId);
            return;
        }
        var connectionRecord = codec.decode(rb);
        var handler = (java.util.function.Function) handshakeHandlers.get(config.connectionType());
        var accepted = (Record) handler.apply(connectionRecord);

        var buf = ByteBuffer.allocate(65536);
        var wb = new WriteBuffer(buf);
        if (accepted != null) {
            wb.writeByte(Handshake.ACCEPTED);
            codec.encode(accepted, wb);
            buf.flip();
            var wireBuf = ByteBuffer.allocate(65536);
            config.pipeline().encodeOutbound(buf, wireBuf);
            wireBuf.flip();
            writeFully(channel, wireBuf);
            handshakePending.remove(connId);
            serviceRegistry.dispatchConnect(connId);
        } else {
            rejectConnection(channel, config, key, connId);
        }
    }

    private void rejectConnection(SocketChannel channel, ListenerConfig config,
                                   SelectionKey key, ConnectionId connId) {
        var buf = ByteBuffer.allocate(16);
        var wb = new WriteBuffer(buf);
        wb.writeByte(Handshake.REJECTED);
        buf.flip();
        var wireBuf = ByteBuffer.allocate(256);
        config.pipeline().encodeOutbound(buf, wireBuf);
        wireBuf.flip();
        writeFully(channel, wireBuf);
        try { channel.close(); } catch (IOException _) {}
        key.cancel();
        handshakePending.remove(connId);
        sessions.release(connId);
        keyToConnection.remove(key);
        connectionToKey.remove(connId);
        connectionConfig.remove(connId);
    }

    private void processInbound(ByteBuffer wire, ConnectionId sender, ListenerConfig config,
                                SocketChannel channel) {
        var frame = config.pipeline().decodeInbound(wire);
        while (frame != null) {
            var rb = new ReadBuffer(frame);
            var message = codec.decode(rb);

            // Dispatch to handler via executor (supports virtual threads)
            executor.execute(() -> {
                var result = serviceRegistry.dispatch(message, sender);
                if (result instanceof Record response) {
                    sendResponse(response, config, channel);
                }
            });

            frame = config.pipeline().decodeInbound(wire);
        }
    }

    private void sendResponse(Record response, ListenerConfig config, SocketChannel channel) {
        var encodeBuf = serverEncodeBuf.get();
        var wireBuf = serverWireBuf.get();
        encodeBuf.clear();
        var wb = new WriteBuffer(encodeBuf);
        codec.encode(response, wb);
        encodeBuf.flip();

        wireBuf.clear();
        config.pipeline().encodeOutbound(encodeBuf, wireBuf);
        wireBuf.flip();

        // Multiple threads (worker loops + executor) may send to the same channel
        // (broadcast + handler reply). Serialize per-channel to prevent interleaved
        // writes from corrupting the framed wire format. writeFully retries on
        // partial writes so back-pressure never silently drops bytes.
        synchronized (channel) {
            writeFully(channel, wireBuf);
        }
    }

    /**
     * Write the full buffer to a non-blocking channel, respecting back-pressure.
     * A partial write (count == 0) triggers spin-wait up to 5s rather than dropping bytes.
     */
    private static void writeFully(SocketChannel channel, ByteBuffer buf) {
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (buf.hasRemaining()) {
            int written;
            try {
                written = channel.write(buf);
            } catch (IOException e) {
                return; // Connection lost — caller sees disconnect on next read
            }
            if (written == 0) {
                if (System.nanoTime() > deadlineNs) return; // Slow consumer — give up
                Thread.onSpinWait();
            }
        }
    }

    public int udpPort(Class<? extends Record> connectionType) {
        var p = boundUdpPorts.get(connectionType);
        if (p == null) throw new IllegalArgumentException("No UDP listener for " + connectionType.getName());
        return p;
    }

    /**
     * Manually close a connection. Fires @OnDisconnect on the handler.
     * Thread-safe — operation is dispatched to the owning worker loop.
     */
    public void closeConnection(ConnectionId connId) {
        var selKey = connectionToKey.get(connId);
        if (selKey == null) return;
        var channel = (SocketChannel) selKey.channel();
        // Dispatch to the loop that owns this connection
        var workerLoop = workerGroup.get(Math.floorMod(connId.index(), workerGroup.size()));
        workerLoop.submit(() -> closeConnectionInternal(connId, selKey, channel));
    }

    private void closeConnectionInternal(ConnectionId connId, java.nio.channels.SelectionKey key, SocketChannel channel) {
        if (!connectionChannels.containsKey(connId)) return; // already closed
        key.cancel();
        try { channel.close(); } catch (IOException _) {}
        serviceRegistry.dispatchDisconnect(connId);
        sessions.release(connId);
        keyToConnection.remove(key);
        connectionToKey.remove(connId);
        connectionConfig.remove(connId);
        connectionChannels.remove(connId);
    }

    public void switchPipeline(ConnectionId connId, Pipeline newPipeline) {
        var oldConfig = connectionConfig.get(connId);
        if (oldConfig != null) {
            connectionConfig.put(connId, new ListenerConfig(
                    oldConfig.connectionType(), oldConfig.transport(), newPipeline, null));
        }
    }

    public int port(Class<? extends Record> connectionType) {
        var p = boundPorts.get(connectionType);
        if (p == null) throw new IllegalArgumentException("No listener for " + connectionType.getName());
        return p;
    }

    public boolean isRunning() {
        return acceptLoop.isRunning();
    }

    @Override
    public void close() {
        acceptLoop.close();
        workerGroup.close();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ListenerConfig> listeners = new ArrayList<>();
        private final List<ServiceBinding> services = new ArrayList<>();
        private final CodecRegistry codec = new CodecRegistry();
        private final ServiceRegistry serviceRegistry = new ServiceRegistry(codec);
        @SuppressWarnings("rawtypes")
        private final Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers = new HashMap<>();
        private java.util.concurrent.Executor executor;
        private int eventLoops = 1;

        public Builder listen(Class<? extends Record> connectionType, Transport transport, Layer... layers) {
            listeners.add(new ListenerConfig(connectionType, transport, new Pipeline(layers), layers));
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T extends Record> Builder onHandshake(Class<T> connectionType,
                                                       java.util.function.Function<T, ? extends Record> handler) {
            codec.register(connectionType);
            handshakeHandlers.put(connectionType, (java.util.function.Function) handler);
            return this;
        }

        public Builder addService(Class<?> handlerClass, Class<? extends Record> connectionType) {
            serviceRegistry.register(handlerClass);
            return this;
        }

        public Builder addService(Object handlerInstance, Class<? extends Record> connectionType) {
            serviceRegistry.register(handlerInstance);
            return this;
        }

        public Builder executor(java.util.concurrent.Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder eventLoops(int count) {
            this.eventLoops = count;
            return this;
        }

        public Server build() {
            return new Server(List.copyOf(listeners), serviceRegistry, codec,
                    Map.copyOf(handshakeHandlers), executor, eventLoops);
        }
    }
}
