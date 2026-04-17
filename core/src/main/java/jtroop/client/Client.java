package jtroop.client;

import jtroop.codec.CodecRegistry;
import jtroop.core.EventLoop;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Layer;
import jtroop.pipeline.LayerContext;
import jtroop.pipeline.Pipeline;
import jtroop.service.ServiceRegistry;
import jtroop.transport.Transport;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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
    // Per-connection LayerContext. Allocated on connect, keyed by connection
    // type (the Client's analogue of ConnectionId). Passed into every
    // pipeline call on the connection's hot path.
    private final Map<Class<? extends Record>, LayerContext> contextsByType = new ConcurrentHashMap<>();
    private final java.util.concurrent.Executor executor;
    private final Set<Class<? extends Record>> datagramMessageTypes;

    // Slot-based pending request ring. Zero-allocation request/response:
    //  - request(): atomic-claim a slot, publish thread, park.
    //  - reader thread: scan for first pending slot, stash raw frame bytes,
    //    mark ready, unpark. NO record allocation on the event-loop thread.
    //  - waiter thread: on wake, decode the record FROM THE STASHED BYTES on
    //    its own stack frame. With the full codec.decode chain inlined, C2
    //    scalar-replaces the record — it never hits the heap.
    // No CompletableFuture per call, no Integer boxing, no HashMap entry.
    // 256 slots supports up to 256 in-flight requests per Client (typical: 1-4).
    private static final int REQ_SLOTS = 256;
    private static final int REQ_MASK = REQ_SLOTS - 1;
    // Per-slot scratch size. Covers typeId (2B) + small primitive response
    // records; grown lazily if a larger response arrives.
    private static final int REQ_SCRATCH_INITIAL = 64;
    private static final VarHandle REQ_WAITER;
    private static final VarHandle REQ_READY;
    static {
        try {
            REQ_WAITER = MethodHandles.arrayElementVarHandle(Thread[].class);
            REQ_READY = MethodHandles.arrayElementVarHandle(int[].class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
    final Thread[] reqWaiters = new Thread[REQ_SLOTS];
    // Per-slot ready flag. 0 = waiting, 1 = response bytes stashed in
    // reqScratch[slot]. Primitive int avoids the heap pin that a Record[]
    // reference slot creates for the cross-thread handoff.
    final int[] reqReady = new int[REQ_SLOTS];
    // Per-slot raw-frame scratch. Reader copies the inbound frame's bytes
    // (including typeId) into scratch; waiter calls codec.decode on its own
    // thread so EA can scalar-replace the result record.
    final ByteBuffer[] reqScratch = new ByteBuffer[REQ_SLOTS];
    {
        for (int i = 0; i < REQ_SLOTS; i++) {
            reqScratch[i] = ByteBuffer.allocate(REQ_SCRATCH_INITIAL);
        }
    }
    // Reader drain cursor: only mutated on the event-loop thread. Scanning
    // from here preserves FIFO response-to-waiter matching (legacy behaviour).
    private int reqDrainCursor = 0;
    // Response-type → pre-resolved generated codec. Populated lazily on first
    // request(msg, T) for a given T so the hot path skips the byId lookup
    // and keeps the .decode(buf) callsite monomorphic per response type.
    private final ConcurrentHashMap<Class<? extends Record>, jtroop.generate.CodecClassGenerator.GeneratedCodec>
            responseCodecCache = new ConcurrentHashMap<>();
    private final Map<Class<? extends Record>, java.util.function.Consumer<Record>> messageHandlers;
    private final Map<Class<? extends Record>, Record> handshakeInstances;
    private final Map<Class<? extends Record>, CompletableFuture<Record>> handshakeResults = new ConcurrentHashMap<>();
    private final Set<Class<? extends Record>> handshakePending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    /**
     * Pre-resolved send context for a specific message type. Caches everything
     * needed by the hot send path — slot/channel, fused pipeline, layer
     * context, and transport-specific fast-path flags. Resolving these once
     * eliminates 5-7 HashMap/ConcurrentHashMap lookups per send.
     *
     * <p>Sealed with two permits so {@code switch(ctx)} in {@link #send} is
     * exhaustive and C2 can profile both branches independently.
     */
    private sealed interface SendCtx {
        record Tcp(int slot,
                   jtroop.generate.FusedPipelineGenerator.FusedPipeline fused,
                   Layer.Context layerCtx,
                   int fixedPayloadSize,
                   boolean singleFraming) implements SendCtx {}
        record Udp(java.nio.channels.DatagramChannel channel,
                   jtroop.generate.FusedPipelineGenerator.FusedPipeline fused,
                   Layer.Context layerCtx,
                   boolean hasLayers,
                   Class<? extends Record> connType) implements SendCtx {}
    }

    // Per-message-type cached send context. ConcurrentHashMap for safe
    // publication from the first-send thread; subsequent reads are a single
    // volatile load + pointer chase (no hashing, no equals).
    private final ConcurrentHashMap<Class<? extends Record>, SendCtx> sendCache = new ConcurrentHashMap<>();

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

        // Allocate per-connection Context. No ConnectionId on the client side
        // — use the slot index packed in the low 32 bits with generation 1.
        java.net.InetSocketAddress peer = null;
        try {
            var remote = channel.getRemoteAddress();
            if (remote instanceof java.net.InetSocketAddress isa) peer = isa;
        } catch (IOException _) {}
        final var connTypeFinal = config.connectionType();
        long packedId = ((long) 1 << 32) | (slot & 0xFFFFFFFFL);
        var ctx = new LayerContext(
                packedId,
                peer,
                System.nanoTime(),
                /* closeAfterFlush */ () -> closeConnection(connTypeFinal),
                /* closeNow */        () -> closeConnection(connTypeFinal));
        contextsByType.put(connTypeFinal, ctx);

        // Send handshake if we have a handshake instance
        var hsInstance = handshakeInstances.get(config.connectionType());
        if (hsInstance != null) {
            handshakePending.add(config.connectionType());
            handshakeResults.put(config.connectionType(), new CompletableFuture<>());
            sendHandshake(channel, config, hsInstance);
        }

        var connType = config.connectionType();
        eventLoop.submit(() -> {
            try {
                var readBuf = ByteBuffer.allocate(65536);
                channel.register(eventLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            if (key.isReadable()) {
                                // Look up the current ConnectionConfig on each
                                // read — switchPipeline rewrites configByType
                                // on this same event loop, so we always see the
                                // pipeline in effect for the current read. Do
                                // not capture `config` directly.
                                var current = configByType.get(connType);
                                if (current == null) current = config; // fall back to original
                                handleRead(key, current, readBuf);
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
        var ctx = contextsByType.get(config.connectionType());
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        config.pipeline().encodeOutbound(lc, buf, wire);
        wire.flip();
        int written = wire.remaining();

        // Non-blocking channel: write may be partial — loop until fully sent or
        // the receiver stops accepting (deadline).
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (wire.hasRemaining()) {
            int w;
            try { w = channel.write(wire); } catch (IOException _) { return; }
            if (w == 0) {
                if (System.nanoTime() > deadlineNs) return;
                Thread.onSpinWait();
            }
        }
        if (ctx != null) ctx.addBytesWritten(written);
    }

    private void connectUdp(ConnectionConfig config) throws IOException {
        var channel = java.nio.channels.DatagramChannel.open();
        // Blocking mode only for pipelined UDP (we run a dedicated read thread
        // that parks inside read()). Non-blocking matches the existing
        // fire-and-forget write behaviour.
        boolean pipelined = config.pipeline().size() > 0;
        channel.configureBlocking(pipelined);
        channel.connect(config.transport().address()); // "connected" UDP — sends to fixed address
        udpChannels.put(config.connectionType(), channel);

        // Allocate a per-connection LayerContext so every UDP layer call sees
        // the peer's InetSocketAddress, the packed connection id, and byte
        // counters. Only meaningful when the pipeline has layers — but cheap
        // regardless, and skipping it would force the LayerContext.NOOP branch
        // permanently which defeats filter layers like AllowListLayer.
        java.net.InetSocketAddress peer = null;
        try {
            var remote = channel.getRemoteAddress();
            if (remote instanceof java.net.InetSocketAddress isa) peer = isa;
        } catch (IOException _) {}
        final var connTypeFinal = config.connectionType();
        // Slot index: pack a synthetic id — UDP doesn't use the event loop's
        // channelSlots since it has no SelectionKey here. Use hashCode so
        // multiple UDP connections produce distinct ids.
        long packedId = ((long) 1 << 32) | (connTypeFinal.hashCode() & 0xFFFFFFFFL);
        var ctx = new LayerContext(
                packedId,
                peer,
                System.nanoTime(),
                /* closeAfterFlush */ () -> closeConnection(connTypeFinal),
                /* closeNow */        () -> closeConnection(connTypeFinal));
        contextsByType.put(connTypeFinal, ctx);

        // Only register the receive loop when the pipeline has layers. Without
        // layers, unidirectional fire-and-forget is the intended use case and
        // we want to preserve the zero-alloc fast path in sendUdpFast — no
        // read, no context lookup on send.
        if (config.pipeline().size() > 0) {
            startUdpReadLoop(channel, config);
        }
    }

    /**
     * Dedicated blocking UDP read thread for pipelined connections. The thread
     * reads raw datagrams, runs them through the fused pipeline (filters,
     * reliability, framing, decompression, etc.), then dispatches through the
     * usual push-handler / response-slot paths. Stops on channel close.
     */
    private final List<Thread> udpReadThreads = new ArrayList<>();

    private void startUdpReadLoop(java.nio.channels.DatagramChannel channel,
                                   ConnectionConfig config) throws IOException {
        var t = new Thread(() -> runUdpReadLoop(channel, config),
                "client-udp-" + config.connectionType().getSimpleName());
        t.setDaemon(true);
        udpReadThreads.add(t);
        t.start();
    }

    private void runUdpReadLoop(java.nio.channels.DatagramChannel channel,
                                 ConnectionConfig config) {
        var readBuf = ByteBuffer.allocate(65536);
        var ctx = contextsByType.get(config.connectionType());
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                readBuf.clear();
                int n = channel.read(readBuf);
                if (n <= 0) continue;
                if (ctx != null) ctx.addBytesRead(n);
                readBuf.flip();
                var fused = config.pipeline().fused();
                var frame = fused.decodeInbound(lc, readBuf);
                if (frame == null || frame.remaining() < 2) continue;
                int typeId = frame.getShort(frame.position()) & 0xFFFF;
                var msgType = codec.classForTypeId(typeId);
                if (msgType == null) continue;
                var pushHandler = messageHandlers.get(msgType);
                if (pushHandler != null) {
                    var rb = new ReadBuffer(frame);
                    var message = codec.decode(rb);
                    pushHandler.accept(message);
                } else {
                    // No push handler — stash frame bytes for a pending waiter
                    // (request/response over UDP).
                    stashResponseFrame(frame);
                }
            }
        } catch (java.nio.channels.ClosedChannelException _) {
            // Normal shutdown
        } catch (IOException _) {
            // Best effort
        }
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
            // Update per-connection read counter before pipeline sees the bytes.
            var ctx = contextsByType.get(config.connectionType());
            if (ctx != null) ctx.addBytesRead(n);
            readBuf.flip();
            // Hot path: fused pipeline. Monomorphic invokevirtual on the
            // hidden class lets C2 inline the whole decode chain; the plain
            // Pipeline.decodeInbound loops over Layer[] via invokeinterface
            // which blocks inlining (CLAUDE.md rule 4).
            var fused = config.pipeline().fused();
            Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
            var frame = fused.decodeInbound(lc, readBuf);
            while (frame != null) {
                if (handshakePending.contains(config.connectionType())) {
                    processHandshakeResponse(frame, config);
                    frame = fused.decodeInbound(lc, readBuf);
                    continue;
                }
                // Peek type id without consuming. If a push handler is
                // registered for this type we must build the Record here
                // (the handler signature demands it). Otherwise this is a
                // response-to-request: stash the raw frame bytes into the
                // waiter's slot and let the waiter decode on its own stack
                // so C2's EA can scalar-replace the record.
                int typeId = frame.getShort(frame.position()) & 0xFFFF;
                var msgType = codec.classForTypeId(typeId);
                var pushHandler = messageHandlers.get(msgType);
                if (pushHandler != null) {
                    var rb = new ReadBuffer(frame);
                    var message = codec.decode(rb);
                    pushHandler.accept(message);
                } else {
                    stashResponseFrame(frame);
                }
                frame = fused.decodeInbound(lc, readBuf);
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

    /**
     * Stash the raw frame bytes (including typeId) into the first pending
     * waiter's slot scratch buffer, mark ready, and unpark the waiter.
     *
     * <p>Runs on the event-loop thread. Crucially, NO Record is constructed
     * here — the record allocation is deferred to the waiter thread's own
     * stack frame where C2's EA can scalar-replace it. The cross-thread
     * handoff carries only primitive bytes, never an object reference that
     * would pin the record to the heap.
     *
     * <p>The scratch buffer is pre-allocated per slot (reqScratch[slot]) and
     * sized for typical small primitive responses; it grows lazily if a
     * larger frame arrives.
     */
    private void stashResponseFrame(ByteBuffer frame) {
        int start = reqDrainCursor;
        for (int i = 0; i < REQ_SLOTS; i++) {
            int slot = (start + i) & REQ_MASK;
            var waiter = (Thread) REQ_WAITER.getAcquire(reqWaiters, slot);
            if (waiter != null) {
                int remaining = frame.remaining();
                ByteBuffer scratch = reqScratch[slot];
                if (scratch.capacity() < remaining) {
                    int newCap = Integer.highestOneBit(remaining - 1) << 1;
                    scratch = ByteBuffer.allocate(newCap);
                    reqScratch[slot] = scratch;
                }
                scratch.clear();
                scratch.put(frame); // advances frame past the consumed bytes
                scratch.flip();
                // setRelease on the ready flag publishes scratch contents to
                // the waiter. Clear the waiter marker before unpark so slot
                // reuse by the same thread can't race with a stale unpark.
                REQ_READY.setRelease(reqReady, slot, 1);
                REQ_WAITER.setRelease(reqWaiters, slot, (Thread) null);
                LockSupport.unpark(waiter);
                reqDrainCursor = (slot + 1) & REQ_MASK;
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void send(Record message) {
        var msgType = (Class<? extends Record>) message.getClass();
        // Fast path: one CHM.get, then pattern-match dispatch.
        var ctx = sendCache.get(msgType);
        if (ctx != null) {
            switch (ctx) {
                case SendCtx.Tcp tcp -> sendTcpFast(message, tcp);
                case SendCtx.Udp udp -> sendUdpFast(message, udp);
            }
            return;
        }
        sendCold(message, msgType);
    }

    /**
     * Hot TCP send with all references pre-resolved (zero map lookups).
     *
     * <p>For fixed-size messages with a single FramingLayer, the encode path
     * is split into two phases (CLAUDE.md rule 3):
     * <ol>
     *   <li><b>Encode</b> (record consumed, EA can scalar-replace): write the
     *       4-byte framing prefix + typeId + fields into the thread-local heap
     *       wireBuf. No synchronized, no intermediate encodeBuf.</li>
     *   <li><b>Stage</b> (record is dead): copy the small encoded frame
     *       (~22 bytes for a position update) into the EventLoop's write
     *       buffer under the per-slot monitor.</li>
     * </ol>
     *
     * <p>Variable-size or complex-pipeline messages fall back to the full
     * two-buffer encode + pipeline + stageWrite path.
     */
    private void sendTcpFast(Record message, SendCtx.Tcp ctx) {
        if (ctx.singleFraming() && ctx.fixedPayloadSize() >= 0) {
            // Phase 1: encode into heap buffer (EA-friendly, no sync).
            var wire = wireBuf.get();
            wire.clear();
            wire.putInt(ctx.fixedPayloadSize()); // FramingLayer length prefix
            codec.encode(message, wire);
            wire.flip();
            // Phase 2: stage into write buffer.
            if (ctx.layerCtx() instanceof LayerContext lc) lc.addBytesWritten(wire.remaining());
            eventLoop.stageWrite(ctx.slot(), wire);
        } else {
            var encode = encodeBuf.get();
            var wire = wireBuf.get();
            encode.clear();
            codec.encode(message, encode);
            encode.flip();
            wire.clear();
            ctx.fused().encodeOutbound(ctx.layerCtx(), encode, wire);
            wire.flip();
            if (ctx.layerCtx() instanceof LayerContext lc) lc.addBytesWritten(wire.remaining());
            eventLoop.stageWrite(ctx.slot(), wire);
        }
    }

    /** Slow path: first-time cache population for TCP or UDP. Runs once per
     *  message type; subsequent sends hit the unified {@link #sendCache}. */
    @SuppressWarnings("unchecked")
    private void sendCold(Record message, Class<? extends Record> msgType) {
        // Try UDP first (datagram types are known at build time).
        if (datagramMessageTypes.contains(msgType)) {
            var connType = resolveConnection(msgType);
            var ch = udpChannels.get(connType);
            if (ch == null) {
                throw new IllegalStateException("No UDP connection for " + msgType.getName());
            }
            var cfg = udpConfigByType.get(connType);
            boolean hasLayers = cfg != null && cfg.pipeline().size() > 0;
            var lctx = contextsByType.get(connType);
            Layer.Context lc = lctx != null ? lctx : LayerContext.NOOP;
            var fused = cfg != null ? cfg.pipeline().fused() : null;
            var udpCtx = new SendCtx.Udp(ch, fused, lc, hasLayers, connType);
            sendCache.put(msgType, udpCtx);
            sendUdpFast(message, udpCtx);
            return;
        }
        // TCP path.
        int encodedBytes = encodeToWire(message);
        if (encodedBytes > 0) {
            var connType = resolveConnection(msgType);
            var slot = channelSlots.get(connType);
            if (slot != null) {
                eventLoop.stageWrite(slot, wireBuf.get());
                // Populate cache for subsequent sends of this type.
                var config = configByType.get(connType);
                var lctx = contextsByType.get(connType);
                Layer.Context lc = lctx != null ? lctx : LayerContext.NOOP;
                var encoder = codec.resolveEncoder((Class<? extends Record>) msgType);
                boolean singleFraming = config.pipeline().size() == 1
                        && config.layers() != null
                        && config.layers().length == 1
                        && config.layers()[0] instanceof jtroop.pipeline.layers.FramingLayer;
                sendCache.put(msgType, new SendCtx.Tcp(
                        slot, config.pipeline().fused(), lc,
                        encoder.fixedPayloadSize(), singleFraming));
            }
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
        codec.encode(message, encode);
        encode.flip();
        return encode.remaining();
    }

    private void sendUdpFast(Record message, SendCtx.Udp ctx) {
        if (encodeUdpInline(message) > 0) {
            if (ctx.hasLayers()) {
                // Connected UDP with a pipeline: encode through the layer
                // stack (framing, sequencing, ack, compression, ...).
                var cfg = udpConfigByType.get(ctx.connType());
                if (cfg != null) {
                    writeUdpThroughPipeline(ctx.channel(), cfg, ctx.connType());
                } else {
                    writeUdpEncoded(ctx.channel());
                }
            } else {
                writeUdpEncoded(ctx.channel());
            }
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
     * Pipeline-aware UDP write. Runs the already-encoded payload through the
     * fused pipeline's encodeOutbound chain (with the per-connection ctx),
     * then writes the resulting wire bytes to the datagram channel.
     */
    private void writeUdpThroughPipeline(java.nio.channels.DatagramChannel udpChannel,
                                          ConnectionConfig cfg,
                                          Class<? extends Record> connType) {
        var encode = encodeBuf.get();
        var wire = wireBuf.get();
        wire.clear();
        var ctx = contextsByType.get(connType);
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        cfg.pipeline().fused().encodeOutbound(lc, encode, wire);
        wire.flip();
        int written = wire.remaining();
        try {
            synchronized (udpChannel) {
                udpChannel.write(wire);
            }
        } catch (IOException _) {
            // UDP send failure — best effort
            return;
        }
        if (ctx != null) ctx.addBytesWritten(written);
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
        codec.encode(message, encode);
        encode.flip();

        wire.clear();
        // Hot path: fused pipeline (monomorphic invokevirtual → inlinable).
        // Plain Pipeline.encodeOutbound uses invokeinterface on Layer[] which
        // blocks C2 inlining and defeats EA on the record/wrapper chain.
        var ctx = contextsByType.get(connType);
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        config.pipeline().fused().encodeOutbound(lc, encode, wire);
        wire.flip();
        int sz = wire.remaining();
        if (ctx != null) ctx.addBytesWritten(sz);
        return sz;
    }


    @SuppressWarnings("unchecked")
    public <T extends Record> T request(Record message, Class<T> responseType) {
        // Zero-alloc request: claim a slot in the flat ring, publish our
        // thread, send, park until reader stashes response bytes + unparks.
        // No CompletableFuture (waiter list alloc), no Map entry, no Integer
        // boxing. The slot index stays as an int local → EA-friendly.
        //
        // The decode happens on THIS thread (after unpark) rather than on
        // the event-loop thread. That keeps the Record's allocation local to
        // a stack frame C2 can inline end-to-end (codec.decode → generated
        // hidden class → new EchoAck(seq)), enabling scalar replacement of
        // the returned record itself — the cross-thread handoff carries only
        // primitive bytes, never an object reference.
        int slot = requestIdCounter.getAndIncrement() & REQ_MASK;
        Thread self = Thread.currentThread();
        // Publish waiter *before* send so the reader can't see a response
        // without also seeing the thread to unpark.
        REQ_WAITER.setRelease(reqWaiters, slot, self);
        send(message);
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while ((int) REQ_READY.getAcquire(reqReady, slot) == 0) {
            long remaining = deadlineNs - System.nanoTime();
            if (remaining <= 0) {
                // Timed out — clear slot so reader doesn't fire a stale unpark.
                REQ_WAITER.setRelease(reqWaiters, slot, (Thread) null);
                throw new RuntimeException("Request failed: timeout");
            }
            LockSupport.parkNanos(remaining);
        }
        // Decode on the caller's stack, bypassing the byId lookup entirely.
        // The scratch ByteBuffer starts at position 0 with limit = frame len.
        // We skip the 2-byte typeId and call the pre-resolved GeneratedCodec
        // for the expected response type directly — no ReadBuffer wrapper,
        // no Map lookup, a monomorphic invokeinterface on a stable target.
        // With the chain fully inlined C2 scalar-replaces the returned record.
        var decoder = responseCodecCache.get(responseType);
        if (decoder == null) {
            // Cold path: resolve the codec and cache it. Subsequent calls take
            // the fast branch above.
            codec.register(responseType);
            decoder = codec.generatedCodecFor(responseType);
            if (decoder != null) responseCodecCache.put(responseType, decoder);
        }
        ByteBuffer scratch = reqScratch[slot];
        Record response;
        if (decoder != null) {
            scratch.position(2); // skip typeId — known from responseType
            response = decoder.decode(scratch);
        } else {
            scratch.position(0);
            response = codec.decode(new ReadBuffer(scratch)); // fallback
        }
        // Clear ready flag for slot reuse. The waiter slot was cleared by
        // the reader when it stashed the response.
        REQ_READY.setRelease(reqReady, slot, 0);
        return (T) response;
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
    // Stable Consumer/BiFunction instances bound once; hands off to generated
    // service proxies. Allocated once per Client → no per-call lambda capture.
    private final java.util.function.Consumer<Record> sendFn = this::send;
    private final java.util.function.BiFunction<Record, Class<?>, Record> requestFn =
            (msg, rt) -> request(msg, (Class<? extends Record>) rt);

    @SuppressWarnings("unchecked")
    public <T> T service(Class<T> serviceInterface) {
        var existing = proxyCache.get(serviceInterface);
        if (existing != null) return (T) existing;
        // Typed hidden-class proxy: each service method becomes a concrete
        // bytecode stub that forwards to sendFn / requestFn. No JDK Proxy,
        // no per-call Object[] args, no reflective dispatch.
        T proxy = jtroop.generate.ServiceProxyGenerator.generate(
                serviceInterface, sendFn, requestFn);
        proxyCache.put(serviceInterface, proxy);
        return proxy;
    }

    /** Flush pending writes immediately. Call after send() for low-latency. */
    public void flush() {
        eventLoop.flush();
    }

    /**
     * Replace the {@link Pipeline} used by the given connection type.
     *
     * <p>The swap happens on the event loop — may be called from any thread.
     * Typical use: the client's {@code @OnMessage(ProtocolUpgrade.Accepted)}
     * handler calls {@code switchPipeline(httpPipeline.replace(HttpLayer.class,
     * new WebSocketLayer()))} after the server has acknowledged the upgrade.
     *
     * <p>See {@code Server.switchPipeline} for the safety contract. Same
     * rules: frame-aligned, stateful-layer-beware.
     *
     * @param connectionType the connection whose pipeline should be swapped
     * @param newPipeline    replacement pipeline
     */
    public void switchPipeline(Class<? extends Record> connectionType, Pipeline newPipeline) {
        var oldConfig = configByType.get(connectionType);
        if (oldConfig == null) return;
        var newConfig = new ConnectionConfig(
                oldConfig.connectionType(), oldConfig.transport(), newPipeline, null);
        eventLoop.submit(() -> configByType.put(connectionType, newConfig));
    }

    /**
     * Convenience overload: swap the pipeline for the client's (single)
     * primary connection. Useful in tests and one-connection clients.
     */
    public void switchPipeline(Pipeline newPipeline) {
        if (configByType.size() != 1) {
            throw new IllegalStateException(
                    "Client has " + configByType.size() + " connections; use switchPipeline(connectionType, pipeline)");
        }
        var connType = configByType.keySet().iterator().next();
        switchPipeline(connType, newPipeline);
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
        contextsByType.remove(connectionType);
    }

    @Override
    public void close() {
        for (var channel : channels.values()) {
            try { channel.close(); } catch (IOException _) {}
        }
        for (var channel : udpChannels.values()) {
            try { channel.close(); } catch (IOException _) {}
        }
        for (var t : udpReadThreads) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
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
            if (transport instanceof jtroop.transport.UdpTransport udp
                    && !udp.connected() && layers != null && layers.length > 0) {
                throw new IllegalArgumentException(
                        "Unconnected UDP (Transport.udp(...)) does not support pipeline layers — "
                                + "per-peer state would be shared across all senders. "
                                + "Use Transport.udpConnected(...) for filter/reliability layers, "
                                + "or drop the layers for a pass-through unconnected UDP client.");
            }
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
