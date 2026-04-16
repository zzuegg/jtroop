package jtroop.client;

import jtroop.codec.CodecRegistry;
import jtroop.core.EventLoop;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Layer;
import jtroop.pipeline.Pipeline;
import jtroop.service.ServiceRegistry;
import jtroop.transport.Transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Client implements AutoCloseable {

    private record ConnectionConfig(
            Class<? extends Record> connectionType,
            Transport transport,
            Pipeline pipeline,
            Layer[] layers
    ) {}

    private final List<ConnectionConfig> connections;
    private final CodecRegistry codec;
    private final Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection;
    private final EventLoop eventLoop;
    // Per-thread encode/wire scratch. The Client encode path is reached from
    // user threads (send/request/sendBlocking) concurrently; a shared ByteBuffer
    // would corrupt the encoded frame when two threads interleave. Each thread
    // gets its own scratch, used only between encode → stageWrite.
    private final ThreadLocal<ByteBuffer> encodeBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(65536));
    private final ThreadLocal<ByteBuffer> wireBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(65536));
    // channels / channelSlots / udpChannels are read on the event loop thread
    // (for handleRead) and mutated/read on external threads (start, send,
    // closeConnection). ConcurrentHashMap for safe publication + lookup.
    private final Map<Class<? extends Record>, SocketChannel> channels = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, Integer> channelSlots = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, java.nio.channels.DatagramChannel> udpChannels = new ConcurrentHashMap<>();
    /**
     * Fast path: message type → UDP channel. Populated lazily on first send so
     * the hot path is a single {@link ConcurrentHashMap#get(Object)} instead of
     * {@code resolveConnection → udpChannels.get}. No {@code @Datagram} routing
     * branch, no service-to-connection indirection.
     */
    private final Map<Class<? extends Record>, java.nio.channels.DatagramChannel> udpChannelByMsgType = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, ConnectionConfig> configByType = new HashMap<>();
    private final Map<Class<? extends Record>, ConnectionConfig> udpConfigByType = new HashMap<>();
    private final java.util.concurrent.Executor executor;
    private final Set<Class<? extends Record>> datagramMessageTypes;
    private final Map<Integer, CompletableFuture<Record>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, java.util.function.Consumer<Record>> messageHandlers;
    private final Map<Class<? extends Record>, Record> handshakeInstances;
    private final Map<Class<? extends Record>, CompletableFuture<Record>> handshakeResults = new ConcurrentHashMap<>();
    private final Set<Class<? extends Record>> handshakePending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    private int nextSlot = 0;

    private Client(List<ConnectionConfig> connections, CodecRegistry codec,
                   Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection,
                   Map<Class<? extends Record>, java.util.function.Consumer<Record>> messageHandlers,
                   Map<Class<? extends Record>, Record> handshakeInstances,
                   Set<Class<? extends Record>> datagramMessageTypes,
                   java.util.concurrent.Executor executor) {
        this.connections = connections;
        this.codec = codec;
        this.serviceToConnection = serviceToConnection;
        this.messageHandlers = messageHandlers;
        this.handshakeInstances = handshakeInstances;
        this.datagramMessageTypes = datagramMessageTypes;
        this.executor = executor != null ? executor : Runnable::run; // default: same thread
        for (var conn : connections) {
            if (conn.transport().isUdp()) {
                udpConfigByType.put(conn.connectionType(), conn);
            } else {
                configByType.put(conn.connectionType(), conn);
            }
        }
        try {
            this.eventLoop = new EventLoop("client-loop");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create event loop", e);
        }
    }

    public void start() throws IOException {
        eventLoop.start();
        for (var config : connections) {
            if (config.transport().isTcp()) {
                connectTcp(config);
            } else if (config.transport().isUdp()) {
                connectUdp(config);
            }
        }
    }

    private void connectTcp(ConnectionConfig config) throws IOException {
        var channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.connect(config.transport().address());
        channel.configureBlocking(false);
        channels.put(config.connectionType(), channel);
        int slot = nextSlot++;
        channelSlots.put(config.connectionType(), slot);
        eventLoop.registerWriteTarget(slot, channel);

        // Send handshake if we have a handshake instance
        var hsInstance = handshakeInstances.get(config.connectionType());
        if (hsInstance != null) {
            handshakePending.add(config.connectionType());
            handshakeResults.put(config.connectionType(), new CompletableFuture<>());
            sendHandshake(channel, config, hsInstance);
        }

        eventLoop.submit(() -> {
            try {
                var readBuf = ByteBuffer.allocate(65536);
                channel.register(eventLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isReadable()) {
                                handleRead(key, config, readBuf);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException("TCP connect failed", e);
            }
        });
    }

    private void sendHandshake(SocketChannel channel, ConnectionConfig config, Record hsInstance) {
        var buf = ByteBuffer.allocate(65536);
        var wb = new WriteBuffer(buf);
        wb.writeInt(jtroop.core.Handshake.MAGIC);
        codec.encode(hsInstance, wb);
        buf.flip();

        var wire = ByteBuffer.allocate(65536);
        config.pipeline().encodeOutbound(buf, wire);
        wire.flip();

        // Non-blocking channel: write may be partial — loop until fully sent or
        // the receiver stops accepting (deadline).
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (wire.hasRemaining()) {
            int written;
            try { written = channel.write(wire); } catch (IOException _) { return; }
            if (written == 0) {
                if (System.nanoTime() > deadlineNs) return;
                Thread.onSpinWait();
            }
        }
    }

    private void connectUdp(ConnectionConfig config) throws IOException {
        var channel = java.nio.channels.DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(config.transport().address()); // "connected" UDP — sends to fixed address
        udpChannels.put(config.connectionType(), channel);
    }

    private void handleRead(SelectionKey key, ConnectionConfig config, ByteBuffer readBuf) throws IOException {
        var channel = (SocketChannel) key.channel();
        int n = channel.read(readBuf);
        if (n == -1) {
            key.cancel();
            channel.close();
            return;
        }
        if (n > 0) {
            readBuf.flip();
            // Hot path: fused pipeline. Monomorphic invokevirtual on the
            // hidden class lets C2 inline the whole decode chain; the plain
            // Pipeline.decodeInbound loops over Layer[] via invokeinterface
            // which blocks inlining (CLAUDE.md rule 4).
            var fused = config.pipeline().fused();
            var frame = fused.decodeInbound(readBuf);
            while (frame != null) {
                if (handshakePending.contains(config.connectionType())) {
                    processHandshakeResponse(frame, config);
                    frame = fused.decodeInbound(readBuf);
                    continue;
                }
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);
                handleIncoming(message);
                frame = fused.decodeInbound(readBuf);
            }
            readBuf.compact();
        }
    }

    private void processHandshakeResponse(ByteBuffer frame, ConnectionConfig config) {
        var rb = new ReadBuffer(frame);
        byte status = rb.readByte();
        var future = handshakeResults.get(config.connectionType());
        if (status == jtroop.core.Handshake.ACCEPTED) {
            var accepted = codec.decode(rb);
            handshakePending.remove(config.connectionType());
            if (future != null) future.complete(accepted);
        } else {
            // Rejected — close connection
            handshakePending.remove(config.connectionType());
            var ch = channels.remove(config.connectionType());
            if (ch != null) { try { ch.close(); } catch (IOException _) {} }
            if (future != null) future.complete(null);
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T handshakeResult(Class<T> acceptedType) {
        // Find the future by accepted type — search all handshake results
        for (var entry : handshakeResults.entrySet()) {
            try {
                var result = entry.getValue().get(5, TimeUnit.SECONDS);
                if (result != null && acceptedType.isInstance(result)) {
                    return (T) result;
                }
            } catch (Exception _) {}
        }
        return null;
    }

    private void handleIncoming(Record message) {
        // First: check if there's a registered push handler for this type
        @SuppressWarnings("unchecked")
        var handler = messageHandlers.get(message.getClass());
        if (handler != null) {
            handler.accept(message);
            return;
        }
        // Then: check if this is a response to a pending request
        for (var entry : pendingRequests.entrySet()) {
            var future = entry.getValue();
            if (!future.isDone()) {
                future.complete(message);
                pendingRequests.remove(entry.getKey());
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void send(Record message) {
        var msgType = (Class<? extends Record>) message.getClass();
        // Two monomorphic fast paths, one branch each — kept compact so C2
        // inlines send() end-to-end and EA scalar-replaces the caller's
        // fresh record.
        var udpChannel = udpChannelByMsgType.get(msgType);
        if (udpChannel != null) {
            sendUdpFast(message, udpChannel);
            return;
        }
        sendTcpOrCold(message, msgType);
    }

    /** Slow path: TCP send or first-time UDP cache population. */
    private void sendTcpOrCold(Record message, Class<? extends Record> msgType) {
        int encodedBytes = encodeToWire(message);
        if (encodedBytes > 0) {
            var connType = resolveConnection(msgType);
            var slot = channelSlots.get(connType);
            if (slot != null) eventLoop.stageWrite(slot, wireBuf.get());
            return;
        }
        if (datagramMessageTypes.contains(msgType)) {
            var connType = resolveConnection(msgType);
            var ch = udpChannels.get(connType);
            if (ch == null) {
                throw new IllegalStateException("No UDP connection for " + msgType.getName());
            }
            udpChannelByMsgType.put(msgType, ch);
            sendUdpFast(message, ch);
        }
    }

    /**
     * Hot UDP encode path. Kept minimal (just codec encode into the encode
     * buffer) so C2 inlines {@code send → sendUdpFast → encodeUdpInline →
     * codec.encode → generated codec}. With the full chain inlined, EA
     * scalar-replaces the caller's fresh record and the WriteBuffer wrapper.
     *
     * <p>Splitting encode from socket write keeps the record's lifetime
     * confined to a method that does NOT perform synchronized / IOException-
     * throwing operations. Those are the boundaries that pin references on
     * the stack and defeat scalar replacement.
     */
    private int encodeUdpInline(Record message) {
        var encode = encodeBuf.get();
        encode.clear();
        codec.encode(message, new WriteBuffer(encode));
        encode.flip();
        return encode.remaining();
    }

    private void sendUdpFast(Record message, java.nio.channels.DatagramChannel udpChannel) {
        if (encodeUdpInline(message) > 0) {
            writeUdpEncoded(udpChannel);
        }
    }

    /**
     * Write the already-encoded bytes from {@code encodeBuf.get()} to the
     * channel. Separated from {@link #encodeUdpInline} so the IOException /
     * synchronized boundary is AFTER the point where the record dies — EA
     * is unaffected by what happens here.
     */
    private void writeUdpEncoded(java.nio.channels.DatagramChannel udpChannel) {
        var encode = encodeBuf.get();
        try {
            // DatagramChannel.write is thread-safe via an internal write lock,
            // but we still need to serialize concurrent sends so another
            // thread's bytes don't interleave into the same datagram buffer.
            synchronized (udpChannel) {
                udpChannel.write(encode);
            }
        } catch (IOException _) {
            // UDP send failure — best effort
        }
    }

    /**
     * Blocking send — encodes, stages to EventLoop, blocks until EventLoop
     * has flushed bytes to the socket. Same path as send() but with completion wait.
     * Comparable to Netty's writeAndFlush().sync().
     *
     * Split into encode (small, inlinable → EA eliminates record) and
     * flush (complex, not inlined → but record is already dead).
     */
    @SuppressWarnings("unchecked")
    public void sendBlocking(Record message) {
        // Phase 1: encode — small method, C2 inlines → record is scalar-replaced
        int encodedBytes = encodeToWire(message);

        // Phase 2: stage + block — record is dead here, only wireBuf bytes matter
        if (encodedBytes > 0) {
            var connType = resolveConnection(message.getClass());
            var slot = channelSlots.get(connType);
            if (slot != null) {
                eventLoop.stageWriteAndFlush(slot, wireBuf.get());
            }
        }
    }

    /**
     * Encode a message into wireBuf. Returns number of encoded bytes.
     * Small enough for C2 to inline → EA can scalar-replace the record.
     */
    private int encodeToWire(Record message) {
        var connType = resolveConnection(message.getClass());
        var config = configByType.get(connType);
        if (config == null) return 0;

        var encode = encodeBuf.get();
        var wire = wireBuf.get();
        encode.clear();
        codec.encode(message, new WriteBuffer(encode));
        encode.flip();

        wire.clear();
        // Hot path: fused pipeline (monomorphic invokevirtual → inlinable).
        // Plain Pipeline.encodeOutbound uses invokeinterface on Layer[] which
        // blocks C2 inlining and defeats EA on the record/wrapper chain.
        config.pipeline().fused().encodeOutbound(encode, wire);
        wire.flip();
        return wire.remaining();
    }


    @SuppressWarnings("unchecked")
    public <T extends Record> T request(Record message, Class<T> responseType) {
        var future = new CompletableFuture<Record>();
        int reqId = requestIdCounter.getAndIncrement();
        pendingRequests.put(reqId, future);
        send(message);
        try {
            return (T) future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(reqId);
            throw new RuntimeException("Request failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Record> resolveConnection(Class<?> messageType) {
        var connType = serviceToConnection.get(messageType);
        if (connType != null) return connType;
        // Fallback: use the first connection
        if (!configByType.isEmpty()) {
            return configByType.keySet().iterator().next();
        }
        throw new IllegalStateException("Cannot resolve connection for " + messageType.getName());
    }

    private final Map<Class<?>, Object> proxyCache = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T service(Class<T> serviceInterface) {
        return (T) proxyCache.computeIfAbsent(serviceInterface, iface ->
                java.lang.reflect.Proxy.newProxyInstance(
                        iface.getClassLoader(),
                        new Class<?>[]{iface},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return method.invoke(this, args);
                            }
                            if (args == null || args.length == 0) return null;
                            var message = (Record) args[0];
                            if (method.getReturnType() == void.class) {
                                send(message);
                                return null;
                            } else {
                                @SuppressWarnings("unchecked")
                                var returnType = (Class<? extends Record>) method.getReturnType();
                                return request(message, returnType);
                            }
                        }));
    }

    /** Flush pending writes immediately. Call after send() for low-latency. */
    public void flush() {
        eventLoop.flush();
    }

    public boolean isConnected(Class<? extends Record> connectionType) {
        var channel = channels.get(connectionType);
        return channel != null && channel.isConnected();
    }

    /**
     * Close a specific connection (by type). Remaining connections stay open.
     */
    public void closeConnection(Class<? extends Record> connectionType) {
        var channel = channels.remove(connectionType);
        if (channel != null) {
            try { channel.close(); } catch (IOException _) {}
        }
        var udp = udpChannels.remove(connectionType);
        if (udp != null) {
            try { udp.close(); } catch (IOException _) {}
            // Drop cached message-type → channel entries pointing at this UDP.
            udpChannelByMsgType.values().removeIf(ch -> ch == udp);
        }
    }

    @Override
    public void close() {
        for (var channel : channels.values()) {
            try { channel.close(); } catch (IOException _) {}
        }
        for (var channel : udpChannels.values()) {
            try { channel.close(); } catch (IOException _) {}
        }
        eventLoop.close();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ConnectionConfig> connections = new ArrayList<>();
        private final CodecRegistry codec = new CodecRegistry();
        private final Map<Class<? extends Record>, Class<? extends Record>> serviceToConnection = new HashMap<>();
        private final Map<Class<? extends Record>, java.util.function.Consumer<Record>> messageHandlers = new HashMap<>();
        private final Map<Class<? extends Record>, Record> handshakeInstances = new HashMap<>();
        private final Set<Class<? extends Record>> datagramMessageTypes = new HashSet<>();
        private java.util.concurrent.Executor executor;

        public Builder connect(Class<? extends Record> connectionType, Transport transport, Layer... layers) {
            connections.add(new ConnectionConfig(connectionType, transport, new Pipeline(layers), layers));
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder connect(Record handshakeInstance, Transport transport, Layer... layers) {
            var connType = (Class<? extends Record>) handshakeInstance.getClass();
            codec.register(connType);
            // Register nested Accepted record if present
            for (var inner : connType.getDeclaredClasses()) {
                if (Record.class.isAssignableFrom(inner) && inner.getSimpleName().equals("Accepted")) {
                    codec.register((Class<? extends Record>) inner);
                }
            }
            handshakeInstances.put(connType, handshakeInstance);
            connections.add(new ConnectionConfig(connType, transport, new Pipeline(layers), layers));
            return this;
        }

        public Builder addService(Class<?> serviceInterface, Class<? extends Record> connectionType) {
            for (var method : serviceInterface.getDeclaredMethods()) {
                boolean isDatagram = method.isAnnotationPresent(jtroop.service.Datagram.class);
                for (var paramType : method.getParameterTypes()) {
                    if (Record.class.isAssignableFrom(paramType)) {
                        @SuppressWarnings("unchecked")
                        var recordType = (Class<? extends Record>) paramType;
                        codec.register(recordType);
                        serviceToConnection.put(recordType, connectionType);
                        if (isDatagram) datagramMessageTypes.add(recordType);
                    }
                }
                if (Record.class.isAssignableFrom(method.getReturnType())) {
                    @SuppressWarnings("unchecked")
                    var returnType = (Class<? extends Record>) method.getReturnType();
                    codec.register(returnType);
                }
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        public <T extends Record> Builder onMessage(Class<T> type, java.util.function.Consumer<T> handler) {
            codec.register(type);
            messageHandlers.put(type, (java.util.function.Consumer<Record>) (java.util.function.Consumer<?>) handler);
            return this;
        }

        public Builder executor(java.util.concurrent.Executor executor) {
            this.executor = executor;
            return this;
        }

        public Client build() {
            return new Client(List.copyOf(connections), codec, Map.copyOf(serviceToConnection),
                    Map.copyOf(messageHandlers), Map.copyOf(handshakeInstances), Set.copyOf(datagramMessageTypes),
                    executor);
        }
    }
}
