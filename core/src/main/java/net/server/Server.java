package net.server;

import net.codec.CodecRegistry;
import net.core.EventLoop;
import net.core.Handshake;
import net.core.ReadBuffer;
import net.core.WriteBuffer;
import net.pipeline.Layer;
import net.pipeline.Pipeline;
import net.service.ServiceRegistry;
import net.session.ConnectionId;
import net.session.SessionStore;
import net.transport.Transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

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
    private final EventLoop eventLoop;
    private final Map<Class<? extends Record>, Integer> boundPorts = new HashMap<>();
    private final Map<Class<? extends Record>, Integer> boundUdpPorts = new HashMap<>();
    private final Map<Class<? extends Record>, java.nio.channels.DatagramChannel> udpChannels = new HashMap<>();
    private final Map<SelectionKey, ConnectionId> keyToConnection = new HashMap<>();
    private final Map<ConnectionId, SelectionKey> connectionToKey = new HashMap<>();
    private final Map<ConnectionId, ListenerConfig> connectionConfig = new HashMap<>();
    @SuppressWarnings("rawtypes")
    private final Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers;
    private final Set<ConnectionId> handshakePending = new HashSet<>();

    @SuppressWarnings("rawtypes")
    private Server(List<ListenerConfig> listeners, ServiceRegistry serviceRegistry,
                   CodecRegistry codec,
                   Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers) {
        this.listeners = listeners;
        this.serviceRegistry = serviceRegistry;
        this.codec = codec;
        this.handshakeHandlers = handshakeHandlers;
        this.sessions = new SessionStore(4096);
        try {
            this.eventLoop = new EventLoop("server-loop");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create event loop", e);
        }
        // Wire broadcast and unicast
        serviceRegistry.setBroadcast(this::broadcastImpl);
        serviceRegistry.setUnicast(this::unicastImpl);
    }

    private void broadcastImpl(Record message) {
        sessions.forEachActive(connId -> {
            var selKey = connectionToKey.get(connId);
            if (selKey != null && selKey.isValid()) {
                var config = connectionConfig.get(connId);
                if (config != null) {
                    sendResponse(message, config, (SocketChannel) selKey.channel());
                }
            }
        });
    }

    private void unicastImpl(ConnectionId target, Record message) {
        var selKey = connectionToKey.get(target);
        if (selKey != null && selKey.isValid()) {
            var config = connectionConfig.get(target);
            if (config != null) {
                sendResponse(message, config, (SocketChannel) selKey.channel());
            }
        }
    }

    public void start() throws IOException {
        eventLoop.start();
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

        eventLoop.submit(() -> {
            try {
                serverChannel.configureBlocking(false);
                serverChannel.register(eventLoop.selector(), SelectionKey.OP_ACCEPT,
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

        eventLoop.submit(() -> {
            try {
                channel.configureBlocking(false);
                var readBuf = ByteBuffer.allocate(65536);
                channel.register(eventLoop.selector(), SelectionKey.OP_READ,
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

        var selKey = clientChannel.register(eventLoop.selector(), SelectionKey.OP_READ,
                (EventLoop.KeyHandler) key -> handleRead(key, connId, config, readBuf));
        keyToConnection.put(selKey, connId);
        connectionToKey.put(connId, selKey);
        connectionConfig.put(connId, config);
        if (!needsHandshake) {
            serviceRegistry.dispatchConnect(connId);
        }
    }

    private void handleRead(SelectionKey key, ConnectionId connId, ListenerConfig config,
                            ByteBuffer readBuf) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n = channel.read(readBuf);
        if (n == -1) {
            // Client disconnected
            key.cancel();
            channel.close();
            serviceRegistry.dispatchDisconnect(connId);
            sessions.release(connId);
            keyToConnection.remove(key);
            connectionToKey.remove(connId);
            connectionConfig.remove(connId);
            return;
        }
        if (n > 0) {
            readBuf.flip();
            if (handshakePending.contains(connId)) {
                processHandshake(readBuf, connId, config, channel, key);
            } else {
                processInbound(readBuf, connId, config, channel);
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
            try { channel.write(wireBuf); } catch (IOException _) {}
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
        try {
            channel.write(wireBuf);
            channel.close();
        } catch (IOException _) {}
        key.cancel();
        handshakePending.remove(connId);
        sessions.release(connId);
        keyToConnection.remove(key);
        connectionToKey.remove(connId);
        connectionConfig.remove(connId);
    }

    private void processInbound(ByteBuffer wire, ConnectionId sender, ListenerConfig config,
                                SocketChannel channel) {
        // Decode through pipeline
        var frame = config.pipeline().decodeInbound(wire);
        while (frame != null) {
            var rb = new ReadBuffer(frame);
            var message = codec.decode(rb);

            // Dispatch to handler
            var result = serviceRegistry.dispatch(message, sender);

            // If there's a response, send it back
            if (result instanceof Record response) {
                sendResponse(response, config, channel);
            }

            frame = config.pipeline().decodeInbound(wire);
        }
    }

    private void sendResponse(Record response, ListenerConfig config, SocketChannel channel) {
        var buf = ByteBuffer.allocate(65536);
        var wb = new WriteBuffer(buf);
        codec.encode(response, wb);
        buf.flip();

        var wire = ByteBuffer.allocate(65536);
        config.pipeline().encodeOutbound(buf, wire);
        wire.flip();

        try {
            channel.write(wire);
        } catch (IOException e) {
            // Connection lost
        }
    }

    public int udpPort(Class<? extends Record> connectionType) {
        var p = boundUdpPorts.get(connectionType);
        if (p == null) throw new IllegalArgumentException("No UDP listener for " + connectionType.getName());
        return p;
    }

    public int port(Class<? extends Record> connectionType) {
        var p = boundPorts.get(connectionType);
        if (p == null) throw new IllegalArgumentException("No listener for " + connectionType.getName());
        return p;
    }

    public boolean isRunning() {
        return eventLoop.isRunning();
    }

    @Override
    public void close() {
        eventLoop.close();
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

        public Server build() {
            return new Server(List.copyOf(listeners), serviceRegistry, codec, Map.copyOf(handshakeHandlers));
        }
    }
}
