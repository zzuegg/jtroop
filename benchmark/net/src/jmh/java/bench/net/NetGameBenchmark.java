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
import jtroop.transport.Transport;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

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
    public record ChatMessage(String text, int room) {}
    public record MoveAck(int ok) {}

    // Service contract
    public interface GameService {
        void position(PositionUpdate pos);
        void chat(ChatMessage msg);
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
    }

    record BenchConn(int v) {}

    private Server server;
    private Client client;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        var handler = new GameHandler();
        server = Server.builder()
                .listen(BenchConn.class, Transport.tcp(0), new FramingLayer())
                .addService(handler, BenchConn.class)
                .build();
        server.start();
        int port = server.port(BenchConn.class);

        client = Client.builder()
                .connect(BenchConn.class, Transport.tcp("localhost", port), new FramingLayer())
                .addService(GameService.class, BenchConn.class)
                .build();
        client.start();
        Thread.sleep(500); // wait for connection
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
}
