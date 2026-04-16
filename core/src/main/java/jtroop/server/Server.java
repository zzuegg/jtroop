package jtroop.server;

import jtroop.codec.CodecRegistry;
import jtroop.core.EventLoop;
import jtroop.core.EventLoopGroup;
import jtroop.core.Handshake;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.Layer;
import jtroop.pipeline.LayerContext;
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
    // For connected-UDP listeners: connection id -> pinned DatagramChannel used
    // to write unicast replies through the same pipeline instance.
    private final Map<ConnectionId, java.nio.channels.DatagramChannel> udpConnectionChannels = new ConcurrentHashMap<>();
    private final Map<ConnectionId, ListenerConfig> connectionConfig = new ConcurrentHashMap<>();
    // Per-connection Layer.Context. One instance per accepted connection,
    // reused across every pipeline call on that connection. The Context holds
    // the packed connection id, peer address, byte counters and
    // closeAfterFlush / closeNow hooks that enqueue onto the owning worker
    // loop.
    private final Map<ConnectionId, LayerContext> connectionContexts = new ConcurrentHashMap<>();
    // Flat per-slot channel table for zero-lookup broadcast fan-out. Indexed
    // by session slot (ConnectionId.index); {@code null} means "slot not in
    // use". Parallel to SessionStore's state arrays — populated on accept,
    // cleared on disconnect. Replaces the per-recipient
    //   ConnectionId.of(index, gen) + connectionChannels.get(id)
    // sequence, which allocated a ConnectionId record that escaped into the
    // non-inlined ConcurrentHashMap.get and defeated scalar replacement
    // (CLAUDE.md rule #3). The array store is plain; publication is
    // piggy-backed on SessionStore.allocate / release synchronized edges
    // which flush the write before a fan-out observer can see active=true
    // for the slot.
    private final SocketChannel[] slotChannels;
    @SuppressWarnings("rawtypes")
    private final Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers;
    private final Set<ConnectionId> handshakePending = ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.Executor executor;
    // True when the executor is the default inline Runnable::run. When true,
    // processInbound calls dispatch directly instead of going through
    // executor.execute(() -> ...) — eliminating the per-frame lambda capture
    // (~40 B) that C2 cannot scalar-replace because the lambda escapes into
    // the non-inlined Executor.execute interface call (CLAUDE.md rule #4).
    private final boolean inlineExecutor;

    @SuppressWarnings("rawtypes")
    private Server(List<ListenerConfig> listeners, ServiceRegistry serviceRegistry,
                   CodecRegistry codec,
                   Map<Class<? extends Record>, java.util.function.Function> handshakeHandlers,
                   java.util.concurrent.Executor executor, int workerCount) {
        this.listeners = listeners;
        this.serviceRegistry = serviceRegistry;
        this.codec = codec;
        this.handshakeHandlers = handshakeHandlers;
        this.inlineExecutor = (executor == null);
        this.executor = executor != null ? executor : Runnable::run;
        this.sessions = new SessionStore(4096);
        this.slotChannels = new SocketChannel[sessions.capacity()];
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

    // Reusable fan-out walker. broadcastImpl is serialized by broadcastLock
    // below so a single instance is safe across worker loops — no per-call
    // allocation (CLAUDE.md rule #7), and the call site at
    // forEachActiveIndex(walker) stays monomorphic (CLAUDE.md rule #4).
    private final BroadcastWalker broadcastWalker = new BroadcastWalker();
    // Broadcast fan-out lock. Serializes broadcastImpl across worker loops
    // for two reasons:
    //   1. Pipeline.tempBuffers is shared state — concurrent encodeOutbound
    //      races on it. The original code only worked because sendResponse
    //      was called from inside SessionStore.forEachActive's synchronized
    //      block, which unintentionally serialized the encode too. Moving
    //      encode out of that lock (to encode once, not N times) removes
    //      that implicit protection, so we add an explicit one.
    //   2. We reuse a single broadcastWalker + the ThreadLocal wireBuf of
    //      whichever thread got the lock. A single encode + fan-out fills
    //      the buffer, then per-recipient writeFully rewinds and sends.
    private final Object broadcastLock = new Object();

    private void broadcastImpl(Record message) {
        synchronized (broadcastLock) {
            // Encode once — not per recipient. The wire bytes are identical
            // for every destination. All active sessions share the same
            // ListenerConfig (one pipeline per connectionType), so we pick
            // any active config and encode against that pipeline.
            ListenerConfig anyConfig = null;
            for (var cfg : connectionConfig.values()) {
                anyConfig = cfg;
                break;
            }
            if (anyConfig == null) return;

            var encodeBuf = serverEncodeBuf.get();
            var wireBuf = serverWireBuf.get();
            encodeBuf.clear();
            codec.encode(message, new WriteBuffer(encodeBuf));
            encodeBuf.flip();

            wireBuf.clear();
            // Broadcast encodes once against any active pipeline — there is no
            // single Context to pin. NOOP is fine: bundled layers that actually
            // observe the ctx (AllowListLayer, RateLimitLayer) are early-decode
            // filters, not encoders.
            anyConfig.pipeline().encodeOutbound(LayerContext.NOOP, encodeBuf, wireBuf);
            wireBuf.flip();

            // Fan out the pre-encoded bytes. No lambda capture.
            broadcastWalker.prepare(wireBuf);
            sessions.forEachActiveIndex(broadcastWalker);
        }
    }

    /**
     * Stateful primitive-spec visitor used by {@link #broadcastImpl}. Holds
     * the pre-encoded wire bytes for the current broadcast and writes them
     * to every active connection's channel. Shared single instance guarded
     * by {@link #broadcastLock} — no per-call alloc.
     */
    private final class BroadcastWalker implements SessionStore.IndexVisitor {
        private ByteBuffer wire;
        private int wireStart;
        private int wireEnd;

        void prepare(ByteBuffer wireBuf) {
            this.wire = wireBuf;
            this.wireStart = wireBuf.position();
            this.wireEnd = wireBuf.limit();
        }

        @Override
        public void visit(int index, int generation) {
            // Zero-alloc lookup: plain aaload on a primitive-indexed array.
            // Previously this did ConnectionId.of(index, generation) +
            // ConcurrentHashMap.get — the record escaped into the non-inlined
            // CHM.get and could not be scalar-replaced (CLAUDE.md rule #3),
            // costing 24 B/recipient. `generation` is unused here:
            // forEachActiveIndex only yields currently-active slots so the
            // slot→channel mapping is already filtered by liveness.
            var channel = slotChannels[index];
            if (channel == null || !channel.isConnected()) return;

            // Rewind the buffer without allocating a duplicate — we own it
            // for the whole fan-out and each writeFully consumes position→limit.
            // limit() must be widened before position() is set if the buffer
            // was fully consumed by the previous iteration.
            wire.limit(wireEnd);
            wire.position(wireStart);
            synchronized (channel) {
                writeFully(channel, wire);
            }
        }
    }

    private void unicastImpl(ConnectionId target, Record message) {
        var channel = connectionChannels.get(target);
        var config = connectionConfig.get(target);
        if (channel != null && channel.isConnected() && config != null) {
            sendResponse(message, config, channel, target);
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

    // Dedicated UDP threads for connected-mode listeners (opt-in fast path).
    // Kept here so {@link #close()} can signal shutdown via interrupt.
    private final List<Thread> udpConnectedThreads = new ArrayList<>();

    private void startUdpListener(ListenerConfig config) throws IOException {
        var channel = java.nio.channels.DatagramChannel.open();
        // Raise SO_RCVBUF: burst-of-packets handoff (benchmarks, reliability
        // layers stacking sends) otherwise gets dropped at the kernel when the
        // default 208 KB fills up before the read thread drains. 4 MB is
        // tiny on a modern host and covers ~2750 MTU-sized datagrams.
        try {
            channel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
        } catch (IOException _) { /* best effort — some OSes cap lower */ }
        channel.bind(config.transport().address());
        int port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        boundUdpPorts.put(config.connectionType(), port);
        udpChannels.put(config.connectionType(), channel);

        // "Connected UDP" opt-in: when the transport is flagged connected, we
        // skip the shared {@link Selector}-driven event loop and run a
        // dedicated blocking thread that calls {@code channel.read(buf)}
        // directly. The shared selector path allocates a {@code HashMap$KeyIterator}
        // plus a {@code HashMap$Node} per packet (JDK selector internals —
        // {@code SelectorImpl.processReadyEvents} + {@code HashSet.iterator});
        // a dedicated thread with a blocking receive avoids both.
        //
        // Multi-peer ({@code Transport.udp(...)}) still uses the shared
        // selector and pays the 32 B/op overhead — needed for the common case
        // where the server must route datagrams from many clients on one port.
        boolean connectedMode = ((jtroop.transport.UdpTransport) config.transport()).connected();

        if (connectedMode) {
            startConnectedUdpListener(channel, config);
        } else {
            startMultiPeerUdpListener(channel, config);
        }
    }

    private void startConnectedUdpListener(java.nio.channels.DatagramChannel channel,
                                           ListenerConfig config) throws IOException {
        // Blocking channel: read() parks the thread in the kernel — no JDK
        // selector, no HashMap iterator allocation per packet.
        channel.configureBlocking(true);

        var thread = new Thread(() -> runConnectedUdpLoop(channel, config),
                "server-udp-connected-" + config.connectionType().getSimpleName());
        thread.setDaemon(true);
        udpConnectedThreads.add(thread);
        thread.start();
    }

    private void runConnectedUdpLoop(java.nio.channels.DatagramChannel channel,
                                      ListenerConfig config) {
        var readBuf = ByteBuffer.allocate(65536);
        // First-packet setup: receive() returns the sender so we can pin the
        // channel via connect(). Subsequent packets use read(), which avoids
        // the per-packet {@link InetSocketAddress} allocation inside receive().
        ConnectionId connId = null;
        LayerContext ctx = null;
        boolean dispatchedConnect = false;
        try {
            while (!Thread.currentThread().isInterrupted() && acceptLoop.isRunning()) {
                readBuf.clear();
                if (!channel.isConnected()) {
                    var remoteAddr = channel.receive(readBuf);
                    if (remoteAddr == null) continue;
                    try { channel.connect(remoteAddr); } catch (IOException _) {}
                    // Promote peer: allocate a per-connection id + LayerContext
                    // so every layer call sees a stable connectionId, the peer's
                    // InetSocketAddress, and close hooks wired to this channel.
                    if (connId == null) {
                        connId = sessions.allocate();
                        final ConnectionId pinnedId = connId;
                        final java.nio.channels.DatagramChannel pinnedChannel = channel;
                        ctx = new LayerContext(
                                connId.id(),
                                (InetSocketAddress) remoteAddr,
                                System.nanoTime(),
                                /* closeAfterFlush */ () -> closeUdpConnection(pinnedId, pinnedChannel),
                                /* closeNow */        () -> closeUdpConnection(pinnedId, pinnedChannel));
                        connectionContexts.put(connId, ctx);
                        connectionConfig.put(connId, config);
                        udpConnectionChannels.put(connId, channel);
                        serviceRegistry.dispatchConnect(connId);
                        dispatchedConnect = true;
                    }
                } else {
                    int n = channel.read(readBuf);
                    if (n <= 0) continue;
                    if (ctx != null) ctx.addBytesRead(n);
                }
                readBuf.flip();
                if (readBuf.remaining() >= 2 && ctx != null) {
                    processUdpInbound(readBuf, connId, config, channel, ctx);
                }
            }
        } catch (ClosedChannelException _) {
            // Normal shutdown (includes AsynchronousCloseException)
        } catch (Throwable t) {
            System.err.println("Server: connected-UDP loop error: " + t);
        } finally {
            if (connId != null && dispatchedConnect) {
                try { serviceRegistry.dispatchDisconnect(connId); } catch (Throwable _) {}
                connectionContexts.remove(connId);
                connectionConfig.remove(connId);
                udpConnectionChannels.remove(connId);
                sessions.release(connId);
            }
        }
    }

    /**
     * Pipeline-aware UDP inbound processing. Runs the fused pipeline over the
     * received datagram, peeks the type id to fast-path {@code @ZeroAlloc} raw
     * handlers, or falls back to codec decode + dispatch. Responses from
     * non-raw handlers go back out through the same pipeline via
     * {@link #sendUdpResponse}.
     */
    private void processUdpInbound(ByteBuffer wire, ConnectionId sender, ListenerConfig config,
                                    java.nio.channels.DatagramChannel channel, LayerContext ctx) {
        var fused = config.pipeline().fused();
        var frame = fused.decodeInbound(ctx, wire);
        if (frame == null || frame.remaining() < 2) return;

        int typeId = frame.getShort() & 0xFFFF;
        if (serviceRegistry.hasRawHandler(typeId)) {
            serviceRegistry.dispatchRaw(typeId, frame, sender);
            return;
        }
        frame.position(frame.position() - 2);
        var rb = new ReadBuffer(frame);
        var message = codec.decode(rb);
        var result = serviceRegistry.dispatch(message, sender);
        if (result instanceof Record response) {
            sendUdpResponse(response, config, channel, sender);
        }
    }

    /**
     * Send a response record on a connected-UDP channel, running it through
     * the pipeline's {@code encodeOutbound} chain with the per-connection
     * {@link LayerContext}. Serializes on the channel (datagrams can still
     * interleave across threads if a handler fans out).
     */
    private void sendUdpResponse(Record response, ListenerConfig config,
                                  java.nio.channels.DatagramChannel channel,
                                  ConnectionId connId) {
        var encodeBuf = serverEncodeBuf.get();
        var wireBuf = serverWireBuf.get();
        encodeBuf.clear();
        codec.encode(response, new WriteBuffer(encodeBuf));
        encodeBuf.flip();

        wireBuf.clear();
        var ctx = connectionContexts.get(connId);
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        config.pipeline().fused().encodeOutbound(lc, encodeBuf, wireBuf);
        wireBuf.flip();
        int written = wireBuf.remaining();
        try {
            synchronized (channel) {
                channel.write(wireBuf);
            }
        } catch (IOException _) {
            // UDP write failure — best effort
            return;
        }
        if (ctx != null) ctx.addBytesWritten(written);
    }

    /** Close callback for a connected-UDP session. Invoked from layer
     *  callbacks (any thread) — unbinds the session store entry and closes the
     *  channel, which ends the blocking read loop. */
    private void closeUdpConnection(ConnectionId connId, java.nio.channels.DatagramChannel channel) {
        try { channel.close(); } catch (IOException _) {}
        // The loop's finally block will release the session + dispatch disconnect.
    }

    private void startMultiPeerUdpListener(java.nio.channels.DatagramChannel channel,
                                           ListenerConfig config) {
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
        // Unavoidable for multi-peer — required to route the response. Use
        // {@link Transport#udpConnected(int)} for the zero-alloc 1:1 fast path.
        var remoteAddr = channel.receive(readBuf);
        if (remoteAddr == null) return;
        readBuf.flip();

        // Synthetic ConnectionId based on remote address hash (simplified —
        // real impl would track UDP sessions).
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

        // Allocate the per-connection Layer.Context once. Close callbacks
        // dispatch through {@link #closeConnection}, which itself routes to
        // the owning worker loop — safe to invoke from any thread.
        InetSocketAddress peer = null;
        try {
            var remote = clientChannel.getRemoteAddress();
            if (remote instanceof InetSocketAddress isa) peer = isa;
        } catch (IOException _) { /* socket may already be torn down */ }
        var ctx = new LayerContext(
                connId.id(),
                peer,
                System.nanoTime(),
                /* closeAfterFlush */ () -> closeConnection(connId),
                /* closeNow */        () -> closeConnection(connId));
        connectionContexts.put(connId, ctx);
        // Publish to the flat per-slot table for zero-lookup broadcast fan-out.
        // Happens-before a concurrent broadcast observer via SessionStore's
        // synchronized methods: sessions.allocate released the active-bit
        // write, and any subsequent forEachActiveIndex acquires the same
        // monitor, flushing this store through the JMM read-of-monitor edge.
        slotChannels[connId.index()] = clientChannel;

        // Assign to a worker loop. switchPipeline dispatches to the loop
        // selected by Math.floorMod(index, size), so this registration must
        // pick the same loop. Round-robin via next() would leave switchPipeline
        // submitting to a different thread than the one running handleRead —
        // the config swap would race the decode loop.
        var workerLoop = workerGroup.get(Math.floorMod(connId.index(), workerGroup.size()));
        workerLoop.submit(() -> {
            try {
                // Look up the current ListenerConfig on each read. switchPipeline
                // rewrites connectionConfig on the owning worker loop; the
                // handler therefore always sees the pipeline that was in effect
                // when the read began, and a pipeline installed after a frame
                // boundary takes effect on the next selector cycle. Do NOT
                // capture `config` here — it would pin the original pipeline
                // for the lifetime of the selection key.
                var selKey = clientChannel.register(workerLoop.selector(), SelectionKey.OP_READ,
                        (EventLoop.KeyHandler) key -> {
                            var current = connectionConfig.get(connId);
                            if (current == null) return; // connection torn down
                            handleRead(key, connId, current, readBuf);
                        });
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
            // Update the per-connection Context byte counter BEFORE the
            // pipeline sees the bytes, so early-filter layers (rate limit,
            // slowloris) see the latest cumulative read.
            var ctx = connectionContexts.get(connId);
            if (ctx != null) ctx.addBytesRead(n);
            readBuf.flip();
            try {
                if (handshakePending.contains(connId)) {
                    processHandshake(readBuf, connId, config, channel, key, ctx);
                } else {
                    processInbound(readBuf, connId, config, channel, ctx);
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
                                   SocketChannel channel, SelectionKey key, LayerContext ctx) {
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        var frame = config.pipeline().decodeInbound(lc, wire);
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
            config.pipeline().encodeOutbound(lc, buf, wireBuf);
            wireBuf.flip();
            int written = wireBuf.remaining();
            writeFully(channel, wireBuf);
            if (ctx != null) ctx.addBytesWritten(written);
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
        var ctx = connectionContexts.get(connId);
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        config.pipeline().encodeOutbound(lc, buf, wireBuf);
        wireBuf.flip();
        int written = wireBuf.remaining();
        writeFully(channel, wireBuf);
        if (ctx != null) ctx.addBytesWritten(written);
        try { channel.close(); } catch (IOException _) {}
        key.cancel();
        handshakePending.remove(connId);
        // Clear the flat slot BEFORE releasing the session — otherwise a
        // concurrent allocate could reuse the slot and race the null-store,
        // leaking a stale channel ref into fan-out.
        slotChannels[connId.index()] = null;
        sessions.release(connId);
        keyToConnection.remove(key);
        connectionToKey.remove(connId);
        connectionConfig.remove(connId);
        connectionContexts.remove(connId);
    }

    private void processInbound(ByteBuffer wire, ConnectionId sender, ListenerConfig config,
                                SocketChannel channel, LayerContext ctx) {
        // Hot path: fused (hidden-class, monomorphic invokevirtual) so C2 can
        // inline the whole layer decode chain and EA can scalar-replace
        // transient objects across it. Plain Pipeline.decodeInbound dispatches
        // each Layer call via invokeinterface on Layer[], which blocks
        // inlining (CLAUDE.md rule 4).
        var fused = config.pipeline().fused();
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        var frame = fused.decodeInbound(lc, wire);
        while (frame != null) {
            // Peek the 2-byte type id before allocating anything. @ZeroAlloc
            // handlers skip codec.decode entirely — no Record, no String, no
            // byte[] — and run inline on this thread so the reused frame view
            // stays valid.
            int typeId = frame.getShort() & 0xFFFF;
            if (serviceRegistry.hasRawHandler(typeId)) {
                // No lambda capture, no executor handoff — monomorphic
                // invokevirtual on the hidden-class RawHandlerInvoker.
                serviceRegistry.dispatchRaw(typeId, frame, sender);
            } else {
                // Fallback: rewind over the type id so codec.decode sees the
                // full frame, decode Record, dispatch via executor as before.
                frame.position(frame.position() - 2);
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);

                if (inlineExecutor) {
                    // Fast path: default inline executor. Call dispatch directly
                    // — no lambda capture, no invokeinterface on Executor. With
                    // the full chain inlined (dispatch → handler → sendResponse)
                    // C2 can scalar-replace the decoded Record AND the handler's
                    // returned response Record (CLAUDE.md rules #3, #4, #7).
                    var result = serviceRegistry.dispatch(message, sender);
                    if (result instanceof Record response) {
                        sendResponse(response, config, channel, sender);
                    }
                } else {
                    // Slow path: user-provided executor (e.g. virtual threads).
                    // Lambda capture is unavoidable here.
                    executor.execute(() -> {
                        var result = serviceRegistry.dispatch(message, sender);
                        if (result instanceof Record response) {
                            sendResponse(response, config, channel, sender);
                        }
                    });
                }
            }

            frame = fused.decodeInbound(lc, wire);
        }
    }

    private void sendResponse(Record response, ListenerConfig config, SocketChannel channel,
                               ConnectionId connId) {
        var encodeBuf = serverEncodeBuf.get();
        var wireBuf = serverWireBuf.get();
        encodeBuf.clear();
        // Use the direct ByteBuffer overload — avoids the per-call
        // new WriteBuffer(buf) wrapper (16 B) that may not be EA'd when
        // sendResponse is too large for C2 to inline end-to-end.
        codec.encode(response, encodeBuf);
        encodeBuf.flip();

        wireBuf.clear();
        // Hot path: fused pipeline (monomorphic invokevirtual) — see processInbound.
        var ctx = connectionContexts.get(connId);
        Layer.Context lc = ctx != null ? ctx : LayerContext.NOOP;
        config.pipeline().fused().encodeOutbound(lc, encodeBuf, wireBuf);
        wireBuf.flip();
        int written = wireBuf.remaining();

        // Multiple threads (worker loops + executor) may send to the same channel
        // (broadcast + handler reply). Serialize per-channel to prevent interleaved
        // writes from corrupting the framed wire format. writeFully retries on
        // partial writes so back-pressure never silently drops bytes.
        synchronized (channel) {
            writeFully(channel, wireBuf);
        }
        if (ctx != null) ctx.addBytesWritten(written);
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
        // Clear the flat slot BEFORE releasing the session — otherwise a
        // concurrent allocate could reuse the slot and race the null-store,
        // leaking a stale channel ref into fan-out.
        slotChannels[connId.index()] = null;
        sessions.release(connId);
        keyToConnection.remove(key);
        connectionToKey.remove(connId);
        connectionConfig.remove(connId);
        connectionChannels.remove(connId);
        connectionContexts.remove(connId);
    }

    /**
     * Replace the {@link Pipeline} used by the given connection.
     *
     * <p>The swap happens on the connection's owning event loop — the caller
     * may invoke this from any thread (executor, @OnMessage handler, external
     * thread). Used for HTTP→WebSocket upgrade, STARTTLS, ALPN, PROXY
     * protocol, multi-protocol port sniffing.
     *
     * <p><b>Safety contract.</b> The swap is frame-aligned: it runs between
     * inbound reads because the event-loop thread serializes the {@code
     * handleRead → processInbound → fused.decodeInbound} chain for that slot.
     * No partial frame is ever observed across the swap boundary — if bytes
     * remain buffered when the swap fires, the next read cycle will feed them
     * through the <em>new</em> pipeline.
     *
     * <p><b>Stateful layers.</b> The new pipeline must either (a) share the
     * existing stateful layer instances (construct via
     * {@code oldPipeline.replace(...)} / {@code .addFirst(...)} so the
     * already-initialised layers carry across), or (b) be designed such that
     * pre-swap protocol state is no longer needed.
     *
     * @param connId      target connection
     * @param newPipeline replacement pipeline
     */
    public void switchPipeline(ConnectionId connId, Pipeline newPipeline) {
        var oldConfig = connectionConfig.get(connId);
        if (oldConfig == null) return; // connection already closed
        var newConfig = new ListenerConfig(
                oldConfig.connectionType(), oldConfig.transport(), newPipeline, null);

        // Dispatch the actual reference swap to the connection's owning worker
        // loop. Running on any other thread risks a mid-decode race: the loop
        // thread may be inside fused().decodeInbound() while an external
        // thread overwrites the config — the half-decoded frame would then be
        // finalised by the new pipeline, silently corrupting the stream.
        //
        // submit() is thread-safe from any producer and is served on the
        // loop's next cycle, between handleRead invocations. The handler that
        // initiated the swap (inside @OnMessage) is already running between
        // frames — decodeInbound returned null to exit the while loop — so
        // the swap sees a clean boundary.
        var workerLoop = workerGroup.get(Math.floorMod(connId.index(), workerGroup.size()));
        workerLoop.submit(() -> connectionConfig.put(connId, newConfig));
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
        // Shut down the connected-UDP dedicated threads. Closing the channel
        // unblocks the blocking read() with AsynchronousCloseException.
        for (var ch : udpChannels.values()) {
            try { ch.close(); } catch (IOException _) {}
        }
        for (var t : udpConnectedThreads) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        }
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
            // Plan A for unconnected UDP: the pipeline is per-connection
            // (SequencingLayer, AckLayer, DuplicateFilterLayer all hold per-peer
            // state) but an unconnected DatagramChannel sees every peer on the
            // same socket. We would silently cross-contaminate their state.
            // Fail loudly so users don't lose their filter/reliability layers.
            if (transport instanceof jtroop.transport.UdpTransport udp
                    && !udp.connected() && layers != null && layers.length > 0) {
                throw new IllegalArgumentException(
                        "Unconnected UDP (Transport.udp(...)) does not support pipeline layers — "
                                + "per-peer state would be shared across all senders. "
                                + "Use Transport.udpConnected(...) for filter/reliability layers, "
                                + "or drop the layers for a pass-through unconnected UDP listener.");
            }
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
