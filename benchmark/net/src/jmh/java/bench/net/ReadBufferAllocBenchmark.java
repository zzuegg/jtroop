package bench.net;

import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Focused microbenchmark for the {@code new ReadBuffer(frame)} allocation on
 * the decode hot path.
 *
 * <p>The production read loop ({@code Server.processInbound},
 * {@code Client.handleRead}) runs the shape:
 * <pre>{@code
 *     var rb = new ReadBuffer(frame);
 *     var message = codec.decode(rb);
 * }</pre>
 * and {@code CodecRegistry.decode} immediately extracts {@code rb.buffer()}
 * and passes the raw {@code ByteBuffer} to the generated hidden-class codec.
 * ReadBuffer never escapes the decode call chain — C2's escape analysis
 * should scalar-replace it.
 *
 * <p>This benchmark isolates three shapes so a regression is attributable:
 * <ul>
 *   <li>{@code decodeViaReadBuffer} — wraps a pre-filled {@link ByteBuffer}
 *       in {@code new ReadBuffer(...)} per invocation, calls
 *       {@code codec.decode(rb)}, and consumes primitive fields of the
 *       decoded record via {@link Blackhole} (record destructured, no
 *       reference escape). Measures the EA-eliminated hot-path shape.
 *       Target: 0 B/op. Any non-zero value is the ReadBuffer wrapper or
 *       the decoded Record leaking out of EA.</li>
 *   <li>{@code decodeViaReadBuffer_recordEscape} — same path but
 *       {@code bh.consume(msg)} keeps the record reference live, so the
 *       Record itself cannot be scalar-replaced. Isolates "wrapper alone
 *       in EA-friendly code" from "wrapper + record both in EA-friendly
 *       code": B/op should be exactly {@code sizeof(Pos)} (32 B on stock
 *       JDK 26 — 12 B header + 16 B fields, padded) meaning the Record
 *       escapes but the ReadBuffer wrapper is still eliminated. If B/op
 *       climbs past the record size, the wrapper has leaked too.</li>
 *   <li>{@code decodeViaReadBuffer_tightLoop} — 64 wraps per invocation
 *       amortises the allocation signal. At 16 B per wrap × 64 = 1024 B/op
 *       if EA fails. If EA holds, still 0 B/op. Makes a tiny per-wrap
 *       allocation easier to detect than the single-wrap variant.</li>
 * </ul>
 *
 * <p>Signal: {@code decodeViaReadBuffer} and
 * {@code decodeViaReadBuffer_tightLoop} should be ~0 B/op after warmup.
 * {@code decodeViaReadBuffer_recordEscape} should be ~{@code sizeof(Pos)} B/op
 * — the Record (which escapes) but not the ReadBuffer wrapper.
 *
 * <p>Measured on JDK 26 + stock EA (agent-a9bf27d3 worktree, 2026-04-16):
 * <pre>
 * decodeViaReadBuffer              ≈ 10⁻⁵ B/op   (0 — wrapper + record both eliminated)
 * decodeViaReadBuffer_recordEscape   32   B/op   (exact Pos record size; wrapper eliminated)
 * decodeViaReadBuffer_tightLoop    ≈ 10⁻⁴ B/op   (~10⁻⁶ per wrap across 64)
 * </pre>
 * Conclusion: {@code new ReadBuffer(...)} is scalar-replaced on the
 * decode hot path; no pooling or wrapper-elimination refactor needed.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class ReadBufferAllocBenchmark {

    public record Pos(float x, float y, float z, float yaw) {}

    private CodecRegistry codec;
    // Pre-encoded bytes containing [typeId, x, y, z, yaw] — no framing prefix,
    // i.e. exactly what codec.decode expects.
    private byte[] encoded;
    // Persistent ByteBuffer reused across invocations; position reset each call.
    private ByteBuffer buf;
    // Tight-loop variant: independent buffers so the position-reset pattern
    // doesn't collapse loop iterations to the same memory writes.
    private static final int TIGHT_LOOP_ITERS = 64;
    private ByteBuffer[] tightBufs;

    @Setup(Level.Trial)
    public void setup() {
        codec = new CodecRegistry();
        codec.register(Pos.class);

        var wb = new WriteBuffer(ByteBuffer.allocate(64));
        codec.encode(new Pos(1f, 2f, 3f, 0.5f), wb);
        wb.buffer().flip();
        encoded = new byte[wb.buffer().remaining()];
        wb.buffer().get(encoded);

        buf = ByteBuffer.allocate(64);
        buf.put(encoded);

        tightBufs = new ByteBuffer[TIGHT_LOOP_ITERS];
        for (int i = 0; i < TIGHT_LOOP_ITERS; i++) {
            tightBufs[i] = ByteBuffer.allocate(64);
            tightBufs[i].put(encoded);
        }
    }

    /**
     * One wrap + decode per invocation. Decoded record's fields are consumed
     * via Blackhole so the record itself can be scalar-replaced (its float
     * fields escape only as primitives). Any residual B/op here after warmup
     * is attributable to the ReadBuffer wrapper or codec internals.
     */
    @Benchmark
    public void decodeViaReadBuffer(Blackhole bh) {
        buf.position(0).limit(encoded.length);
        var rb = new ReadBuffer(buf);
        var msg = codec.decode(rb);
        if (msg instanceof Pos p) {
            bh.consume(p.x());
            bh.consume(p.y());
            bh.consume(p.z());
            bh.consume(p.yaw());
        }
    }

    /**
     * Same wrap+decode as {@link #decodeViaReadBuffer(Blackhole)} but keeps
     * the decoded Record alive via {@code bh.consume(msg)}, which forces the
     * Record to escape. Purpose: separate the "is the wrapper allocated"
     * question from the "is the record allocated" question.
     *
     * <p>Expected: B/op ≈ sizeof(Pos) — the Record header+fields only. If
     * B/op is larger, the wrapper has leaked out of EA too.
     */
    @Benchmark
    public void decodeViaReadBuffer_recordEscape(Blackhole bh) {
        buf.position(0).limit(encoded.length);
        var rb = new ReadBuffer(buf);
        var msg = codec.decode(rb);
        bh.consume(msg);
    }

    /**
     * Amortised tight loop — 64 independent wraps per invocation. At 64 wraps
     * × 16 B per ReadBuffer instance = 1024 B/op if EA fails. If EA succeeds,
     * still 0 B/op. Makes a tiny per-wrap allocation easier to spot than the
     * single-wrap variant.
     */
    @Benchmark
    public void decodeViaReadBuffer_tightLoop(Blackhole bh) {
        int sink = 0;
        for (int i = 0; i < TIGHT_LOOP_ITERS; i++) {
            var b = tightBufs[i];
            b.position(0).limit(encoded.length);
            var rb = new ReadBuffer(b);
            var msg = codec.decode(rb);
            if (msg instanceof Pos p) {
                sink += Float.floatToRawIntBits(p.x());
            }
        }
        bh.consume(sink);
    }
}
