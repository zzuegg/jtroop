package bench.net;

import bench.GameMessages;
import jtroop.client.Client;
import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import jtroop.pipeline.layers.FramingLayer;
import jtroop.server.Server;
import jtroop.service.*;
import jtroop.session.ConnectionId;
import jtroop.session.SessionStore;
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * JMH benchmark: Our net module game server processing position updates + chat messages.
 * Measures throughput and GC allocation rate — targeting 0 B/op.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class NetGameBenchmark {

    // Message records
    public record PositionUpdate(float x, float y, float z, float yaw) {}
    public record ChatMessage(CharSequence text, int room) {}
    /** Same wire shape as ChatMessage but a distinct type id so a zero-alloc
     *  handler can be registered alongside the record-path handler without
     *  colliding. Identical payload keeps the comparison honest. */
    public record ChatMessageZ(String text, int room) {}
    public record MoveAck(int ok) {}
    public record EchoMsg(int seq) {}
    public record EchoAck(int seq) {}

    // Service contract
    public interface GameService {
        void position(PositionUpdate pos);
        void chat(ChatMessage msg);
        void chatZ(ChatMessageZ msg);
    }

    public interface EchoService {
        EchoAck echo(EchoMsg msg);
    }

    // Handler
    @Handles(GameService.class)
    public static class GameHandler {
        @OnMessage void position(PositionUpdate pos, ConnectionId sender) {
            // Process position — in real game: update world state
        }

        @OnMessage void chat(ChatMessage msg, ConnectionId sender) {
            // Process chat — in real game: broadcast
        }

        /**
         * Zero-alloc chat handler. Framework skips codec.decode entirely —
         * no ChatMessageZ record, no {@code new String(byte[], UTF_8)}, no
         * intermediate byte[] — and hands us the payload view directly.
         * Buffer position is at the start of the message body (after the
         * 2-byte type id, which Server already consumed).
         *
         * Wire layout (matches StringCodec + IntCodec):
         *   u16 textLen  |  textLen UTF-8 bytes  |  s32 room
         *
         * A real handler would either skip the bytes (as here), decode
         * incrementally into reusable scratch, or forward the raw bytes
         * straight to a broadcast encoder — all zero-alloc.
         */
        @OnMessage @ZeroAlloc(ChatMessageZ.class)
        void chatZ(java.nio.ByteBuffer payload, ConnectionId sender) {
            int textLen = payload.getShort() & 0xFFFF;
            // Skip the UTF-8 text bytes — a real handler could checksum them
            // or copy into reusable scratch; either way, zero alloc.
            payload.position(payload.position() + textLen);
            int room = payload.getInt();
            // Touch `room` so the read isn't dead-code eliminated.
            if (room == Integer.MIN_VALUE) throw new AssertionError("unreachable");
        }
    }

    @Handles(EchoService.class)
    public static class EchoHandler {
        @OnMessage EchoAck echo(EchoMsg msg, ConnectionId sender) {
            return new EchoAck(msg.seq());
        }
    }

    record BenchConn(int v) {}

    private Server server;
    private Client client;
    private EchoService echo;

    // --- sessionIteration state ---
    // N=100 active connections sparsely distributed in a 4096-slot store,
    // mirroring the Server's default session capacity.
    private static final int SESSION_CAPACITY = 4096;
    private static final int SESSION_ACTIVE = 100;
    private SessionStore sessionStore;
    // Cached field-captured consumers so the per-invocation lambda capture
    // allocation is not mistaken for iteration cost (CLAUDE.md rule #7).
    private Consumer<ConnectionId> recordSink;
    private LongConsumer longSink;
    private long iterChecksum;
    // Reusable snapshot buffer for activeCopyIds (no per-call long[] alloc).
    private long[] snapshotBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var handler = new GameHandler();
        var echoHandler = new EchoHandler();
        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .addService(echoHandler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, BenchConn.class)
                .addService(EchoService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500); // wait for connection

        echo = client.service(EchoService.class);
        // warm up request/response path
        for (int i = 0; i < 100; i++) echo.echo(new EchoMsg(i));

        // Seed the TcpSendCtx cache — the first send resolves and caches the
        // slot, pipeline, codec, etc. Subsequent sends take the zero-lookup
        // fast path. JMH warmup iterations handle C2 compilation.
        client.send(new PositionUpdate(0f, 0f, 0f, 0f));
        Thread.sleep(50); // let server drain

        // --- Build a sparse N=100 active session set for sessionIteration.
        sessionStore = new SessionStore(SESSION_CAPACITY);
        int stride = SESSION_CAPACITY / SESSION_ACTIVE; // 40
        var allocated = new ConnectionId[SESSION_CAPACITY];
        for (int i = 0; i < SESSION_CAPACITY; i++) {
            allocated[i] = sessionStore.allocate();
        }
        // Release everything except every `stride`-th slot so the remaining
        // active set is evenly distributed across the capacity.
        for (int i = 0; i < SESSION_CAPACITY; i++) {
            if (i % stride != 0) {
                sessionStore.release(allocated[i]);
            }
        }
        // Expected exactly SESSION_ACTIVE + 1 due to integer rounding; trim extras.
        while (sessionStore.activeCount() > SESSION_ACTIVE) {
            for (int i = 1; i < SESSION_CAPACITY; i++) {
                if (sessionStore.activeCount() <= SESSION_ACTIVE) break;
                if (sessionStore.isActive(allocated[i])) {
                    sessionStore.release(allocated[i]);
                    break;
                }
            }
        }

        recordSink = id -> iterChecksum ^= id.id();
        longSink = id -> iterChecksum ^= id;
        snapshotBuf = new long[SESSION_CAPACITY];

        // Pre-warm branch profiles (CLAUDE.md rule #8) — 10K invocations so
        // C1 inlines the cold paths.
        for (int i = 0; i < 10_000; i++) {
            sessionStore.forEachActive(recordSink);
            sessionStore.forEachActiveLong(longSink);
            sessionStore.activeCopyIds(snapshotBuf);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Benchmark
    public void positionUpdate() {
        client.send(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void positionUpdate_blocking() {
        client.sendBlocking(new PositionUpdate(1.0f, 2.0f, 3.0f, 0.5f));
    }

    @Benchmark
    public void chatMessage() {
        client.send(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public void chatMessage_blocking() {
        client.sendBlocking(new ChatMessage(GameMessages.CHAT_TEXT, 1));
    }

    /**
     * Opt-in zero-alloc path: same wire payload as {@link #chatMessage} but
     * the server handler is declared {@code @ZeroAlloc(ChatMessageZ.class)} and
     * takes a raw {@link java.nio.ByteBuffer} instead of the decoded record.
     * Expected: no {@code new String(byte[], UTF_8)} on receive → receive-side
     * B/op collapses (target ≤ 50 B/op total, vs the ~416 B/op the legacy
     * record path costs).
     */
    @Benchmark
    public void chatMessage_zeroAlloc() {
        client.send(new ChatMessageZ(GameMessages.CHAT_TEXT, 1));
    }

    @Benchmark
    public EchoAck requestResponse() {
        return echo.echo(new EchoMsg(1));
    }

    @Benchmark
    public void mixedTraffic() {
        // 80% position updates, 20% chat (typical game workload)
        for (int i = 0; i < 10; i++) {
            if (i < 8) {
                client.send(new PositionUpdate(i * 0.1f, i * 0.2f, i * 0.3f, i * 0.01f));
            } else {
                client.send(new ChatMessage(GameMessages.CHAT_TEXT, i));
            }
        }
    }

    /**
     * Isolation harness: same 10-iteration loop shape as {@link #mixedTraffic}
     * but only sends position updates. Confirms the per-call overhead of
     * {@code client.send(Record)} at a monomorphic callsite — divergence from
     * 10 × positionUpdate's 0.019 B/op reveals loop-scope allocation.
     */
    @Benchmark
    public void mixedTraffic_positionOnly() {
        for (int i = 0; i < 10; i++) {
            client.send(new PositionUpdate(i * 0.1f, i * 0.2f, i * 0.3f, i * 0.01f));
        }
    }

    /**
     * Isolation harness: same 10-iteration loop shape as {@link #mixedTraffic}
     * but only sends chat messages. Compare against 10 × chatMessage to see
     * whether the mixed-type polymorphic dispatch in {@link #mixedTraffic} is
     * the regression source or whether it's per-call overhead.
     */
    @Benchmark
    public void mixedTraffic_chatOnly() {
        for (int i = 0; i < 10; i++) {
            client.send(new ChatMessage(GameMessages.CHAT_TEXT, i));
        }
    }

    // --- Read-loop microbenchmarks --------------------------------------------
    //
    // Simulate Server.handleRead / Client.handleRead on a pre-filled readBuf
    // (no sockets). Exercises exactly the inner drain loop:
    //   pipeline.decodeInbound(readBuf) → new ReadBuffer(frame) → codec.decode
    //   → dispatch-or-consumer → next frame.
    //
    // Target: ~0 B/op per frame. Any residual allocation here multiplies by
    // messages/sec in a real server. These numbers isolate the TCP read path
    // from the write path measured by positionUpdate/chatMessage above.

    @State(Scope.Benchmark)
    public static class ReadLoopState {
        // How many framed messages are pre-encoded into the read buffer.
        // 8 keeps the buffer small and is representative of a single selector
        // cycle draining a few messages per connection.
        static final int FRAMES_PER_INVOCATION = 8;

        ByteBuffer readBuf;         // pre-filled wire bytes; reset each invocation
        ByteBuffer sourceBuf;       // immutable copy of the pre-encoded bytes
        CodecRegistry codec;
        jtroop.pipeline.Pipeline pipeline;
        ServiceRegistry registry;
        final ConnectionId sender = ConnectionId.of(1, 1);
        // Server.processInbound executor is typically "Runnable::run" in the
        // benchmark — a method reference that still requires allocating a new
        // lambda object per frame because the body captures 4 locals.
        final java.util.concurrent.Executor executor = Runnable::run;
        // Accumulates a primitive (not a reference) so the decoded record can
        // still be EA'd: only its fields escape to the int accumulator. This
        // mirrors a realistic handler that reads record components and drops
        // the record.
        int sink;

        @Setup(Level.Trial)
        public void setup() {
            codec = new CodecRegistry();
            codec.register(PositionUpdate.class);

            pipeline = new jtroop.pipeline.Pipeline(new FramingLayer());

            registry = new ServiceRegistry(codec);
            registry.register(new GameHandler());

            // Pre-encode FRAMES_PER_INVOCATION position-update frames into a
            // byte-level source buffer. Each frame = 4-byte framing length +
            // 2-byte type id + 4×4-byte floats = 22 bytes.
            var encode = ByteBuffer.allocate(64);
            var wire = ByteBuffer.allocate(256);
            sourceBuf = ByteBuffer.allocate(FRAMES_PER_INVOCATION * 64);

            for (int i = 0; i < FRAMES_PER_INVOCATION; i++) {
                encode.clear();
                codec.encode(new PositionUpdate(i, i, i, i), new WriteBuffer(encode));
                encode.flip();

                wire.clear();
                pipeline.encodeOutbound(encode, wire);
                wire.flip();

                sourceBuf.put(wire);
            }
            sourceBuf.flip();

            readBuf = ByteBuffer.allocate(65536);

            // Pre-warm the read-loop drain path so C2 profiles it hot.
            for (int i = 0; i < 20_000; i++) drainOnce();
        }

        /** Reset readBuf to the pre-encoded bytes, in write mode (for callers). */
        void refillReadBuf() {
            readBuf.clear();
            int pos = sourceBuf.position();
            int lim = sourceBuf.limit();
            readBuf.put(sourceBuf);
            // Restore sourceBuf for the next call — we only read from it.
            sourceBuf.position(pos);
            sourceBuf.limit(lim);
        }

        /** Client-shaped drain: decode frame → codec.decode → read a field.
         *  Mirrors Client.handleRead's inner loop when a push handler exists
         *  for the message type — reads fields off the record and drops it. */
        int drainOnce() {
            refillReadBuf();
            readBuf.flip();
            int frames = 0;
            int acc = sink;
            var p = pipeline;
            var frame = p.decodeInbound(readBuf);
            while (frame != null) {
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);
                // Touch a primitive field so the record is "used" but does
                // not escape to the heap.
                if (message instanceof PositionUpdate pu) {
                    acc += Float.floatToRawIntBits(pu.x());
                }
                frames++;
                frame = p.decodeInbound(readBuf);
            }
            readBuf.compact();
            sink = acc;
            return frames;
        }

        /** Server-shaped drain: same as client but dispatches through the
         *  ServiceRegistry the same way Server.processInbound does. Excludes
         *  the per-frame executor.execute lambda (that's a separate concern). */
        int drainOnceDispatch() {
            refillReadBuf();
            readBuf.flip();
            int frames = 0;
            var p = pipeline;
            var frame = p.decodeInbound(readBuf);
            while (frame != null) {
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);
                registry.dispatch(message, sender);
                frames++;
                frame = p.decodeInbound(readBuf);
            }
            readBuf.compact();
            return frames;
        }

        /** Server-shaped drain mirroring the exact structure of
         *  Server.processInbound: dispatch happens inside
         *  {@code executor.execute(() -> ...)} — the lambda captures
         *  {@code message}, {@code sender}, and a response-send target. */
        int drainOnceExecutor() {
            refillReadBuf();
            readBuf.flip();
            int frames = 0;
            var p = pipeline;
            var frame = p.decodeInbound(readBuf);
            while (frame != null) {
                var rb = new ReadBuffer(frame);
                var message = codec.decode(rb);
                // Same shape as Server.processInbound: async dispatch via
                // Executor. With the default Runnable::run executor the task
                // runs inline, but the lambda object itself is still allocated
                // unless EA can scalar-replace it (requires executor inlining).
                final Record msg = message;
                executor.execute(() -> registry.dispatch(msg, sender));
                frames++;
                frame = p.decodeInbound(readBuf);
            }
            readBuf.compact();
            return frames;
        }
    }

    /** Client-side read loop: decode framing + codec + primitive-field touch. */
    @Benchmark
    public int readLoop_clientDrain(ReadLoopState s) {
        return s.drainOnce();
    }

    /** Server-side read loop: decode framing + codec + ServiceRegistry dispatch. */
    @Benchmark
    public int readLoop_serverDrain(ReadLoopState s) {
        return s.drainOnceDispatch();
    }

    /** Server read loop including the per-frame executor.execute lambda — the
     *  exact shape of Server.processInbound. Confirms the lambda capture is
     *  EA-eliminated when the executor is monomorphic ({@code Runnable::run}). */
    @Benchmark
    public int readLoop_serverExecutor(ReadLoopState s) {
        return s.drainOnceExecutor();
    }

    // --- Direct dispatch microbenchmarks -------------------------------------
    //
    // Isolate ServiceRegistry.dispatch from the transport stack — no sockets,
    // no framing, no codec, no executor. Exercises exactly the per-message
    // path:  handlers.get → monomorphic invokevirtual on the hidden-class
    // HandlerInvoker → user handler → return.
    //
    // Complements readLoop_serverDrain (dispatch + decode) and the full
    // chatMessage (dispatch + decode + framing + socket + encode). Target
    // for all four shapes is ≈ 0 B/op.

    public record DispatchPos(float x, float y, float z, float yaw) {}
    public record DispatchChat(int room) {}
    public record DispatchAck(int code) {}
    public record DispatchEcho(int seq) {}
    public record DispatchPing(long id) {}

    public interface DispatchSvc {
        void pos(DispatchPos p);
        DispatchAck chat(DispatchChat c);
        void echo(DispatchEcho e);
        void ping(DispatchPing p);
    }

    @Handles(DispatchSvc.class)
    public static class DispatchVoidHandler {
        @OnMessage void pos(DispatchPos p, ConnectionId sender) {}
    }

    @Handles(DispatchSvc.class)
    public static class DispatchReturningHandler {
        // Returning a shared constant isolates dispatch return handling from
        // the user-side record allocation, which would otherwise add 16 B/op.
        static final DispatchAck SHARED_ACK = new DispatchAck(0);
        @OnMessage DispatchAck chat(DispatchChat c, ConnectionId sender) { return SHARED_ACK; }
    }

    @Handles(DispatchSvc.class)
    public static class DispatchBroadcastHandler {
        @OnMessage void echo(DispatchEcho e, ConnectionId sender, Broadcast broadcast) {}
    }

    @Handles(DispatchSvc.class)
    public static class DispatchAllHandler {
        @OnMessage void ping(DispatchPing p, ConnectionId sender,
                              Broadcast broadcast, Unicast unicast) {}
    }

    @State(Scope.Benchmark)
    public static class DirectDispatchState {
        ServiceRegistry voidReg;
        ServiceRegistry returningReg;
        ServiceRegistry broadcastReg;
        ServiceRegistry allReg;

        final DispatchPos pos = new DispatchPos(1f, 2f, 3f, 0.5f);
        final DispatchChat chat = new DispatchChat(42);
        final DispatchEcho echo = new DispatchEcho(7);
        final DispatchPing ping = new DispatchPing(0xDEADBEEFL);
        final ConnectionId sender = ConnectionId.of(1, 1);

        @Setup(Level.Trial)
        public void setup() {
            voidReg = new ServiceRegistry(new CodecRegistry());
            voidReg.register(new DispatchVoidHandler());

            returningReg = new ServiceRegistry(new CodecRegistry());
            returningReg.register(new DispatchReturningHandler());

            broadcastReg = new ServiceRegistry(new CodecRegistry());
            broadcastReg.register(new DispatchBroadcastHandler());
            broadcastReg.setBroadcast(Broadcast.NO_OP);
            broadcastReg.setUnicast(Unicast.NO_OP);

            allReg = new ServiceRegistry(new CodecRegistry());
            allReg.register(new DispatchAllHandler());
            allReg.setBroadcast(Broadcast.NO_OP);
            allReg.setUnicast(Unicast.NO_OP);

            // Pre-warm so C2 compiles the hot path with real profiles
            // (CLAUDE.md rule #8).
            for (int i = 0; i < 20_000; i++) {
                voidReg.dispatch(pos, sender);
                returningReg.dispatch(chat, sender);
                broadcastReg.dispatch(echo, sender);
                allReg.dispatch(ping, sender);
            }
        }
    }

    // --- Broadcast fan-out benchmark ----------------------------------------
    //
    // Measures the server-side broadcast path: one client sends a tick, the
    // server's handler broadcasts it to every connected client. The critical
    // hot path we want at ~0 B/op is Server#broadcastImpl + SessionStore scan
    // + per-recipient wire write, for N recipients.
    //
    // gc.alloc.rate.norm here is reported per benchmark invocation (one
    // trigger message from the sender) but captures allocations on all JVM
    // threads — including the server event loops doing the fan-out.

    public record BroadcastTick(int seq) {}

    public interface BroadcastService {
        void tick(BroadcastTick t);
    }

    @Handles(BroadcastService.class)
    public static class BroadcastHandler {
        @OnMessage void tick(BroadcastTick t, ConnectionId sender, Broadcast broadcast) {
            broadcast.send(t);
        }
    }

    record BroadcastConn(int v) {}

    @State(Scope.Benchmark)
    public static class BroadcastState {
        @Param({"10", "100"})
        public int clients;

        Server server;
        Client[] connected;
        Client sender;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            var handler = new BroadcastHandler();
            server = Server.builder()
                    .listen(BroadcastConn.class, Transport.tcp(0), new FramingLayer())
                    .addService(handler, BroadcastConn.class)
                    .build();
            server.start();
            int port = server.port(BroadcastConn.class);

            connected = new Client[clients];
            for (int i = 0; i < clients; i++) {
                connected[i] = Client.builder()
                        .connect(BroadcastConn.class, Transport.tcp("localhost", port), new FramingLayer())
                        .addService(BroadcastService.class, BroadcastConn.class)
                        .onMessage(BroadcastTick.class, _ -> { /* swallow */ })
                        .build();
                connected[i].start();
            }
            sender = connected[0];
            // Wait for all connections to establish on the server side.
            Thread.sleep(500);

            // Pre-warm the dispatch + broadcast path so C2 profiles the hot
            // loop at actual fan-out size (CLAUDE.md rule #8).
            for (int i = 0; i < 2_000; i++) {
                sender.send(new BroadcastTick(i));
            }
            Thread.sleep(200);
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (connected != null) {
                for (var c : connected) {
                    if (c != null) c.close();
                }
            }
            if (server != null) server.close();
        }
    }

    @Benchmark
    public void broadcastFanOut(BroadcastState state) {
        state.sender.send(new BroadcastTick(1));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void dispatchDirect_void(DirectDispatchState s, Blackhole bh) {
        bh.consume(s.voidReg.dispatch(s.pos, s.sender));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void dispatchDirect_returning(DirectDispatchState s, Blackhole bh) {
        bh.consume(s.returningReg.dispatch(s.chat, s.sender));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void dispatchDirect_broadcast(DirectDispatchState s, Blackhole bh) {
        bh.consume(s.broadcastReg.dispatch(s.echo, s.sender));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void dispatchDirect_allInjectables(DirectDispatchState s, Blackhole bh) {
        bh.consume(s.allReg.dispatch(s.ping, s.sender));
    }

    // --- Session iteration microbenchmarks -----------------------------------
    //
    // Iterate an N=100 active connection set in a 4096-slot SessionStore
    // (sparsely distributed). Exercises the scan used by broadcast fan-out
    // and the GC sweep. Targets zero allocation in all shapes.

    /**
     * Iterate N=100 active connections via the record-yielding
     * {@link SessionStore#forEachActive} API. Target: 0 B/op — the
     * {@link ConnectionId} record built inside the scan must be
     * scalar-replaced by C2 (CLAUDE.md rule #2).
     */
    @Benchmark
    public void sessionIteration() {
        sessionStore.forEachActive(recordSink);
    }

    /**
     * Iterate the same N=100 set via the primitive-long API. This is the
     * broadcast/GC-sweep fast path — no ConnectionId record at all, so EA
     * is a non-issue. Expected: 0 B/op unconditionally.
     */
    @Benchmark
    public void sessionIteration_long() {
        sessionStore.forEachActiveLong(longSink);
    }

    /**
     * Snapshot the active set into a caller-owned {@code long[]} and loop.
     * Models the "iterate without holding the store monitor" pattern used
     * by deferred-mutation callers. Reusable buffer → 0 B/op.
     */
    @Benchmark
    public void sessionIteration_snapshot(Blackhole bh) {
        int n = sessionStore.activeCopyIds(snapshotBuf);
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc ^= snapshotBuf[i];
        }
        bh.consume(acc);
    }

    /**
     * Realistic caller pattern: fresh lambda per invocation that captures a
     * local Blackhole. Stresses whether the record + lambda capture can both
     * be scalar-replaced at the invokeinterface callsite.
     */
    @Benchmark
    public void sessionIteration_freshLambda(Blackhole bh) {
        sessionStore.forEachActive(id -> bh.consume(id.id()));
    }
}
