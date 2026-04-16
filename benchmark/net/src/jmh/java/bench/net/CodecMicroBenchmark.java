package bench.net;

import jtroop.codec.CodecRegistry;
import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Encode / decode microbenchmarks for the codec, isolated from I/O and the
 * full pipeline.
 *
 * <ul>
 *   <li>{@code encodeOnly} — write a pre-created record into a reused
 *       {@link ByteBuffer}. Reports pure "record → wire bytes" cost.</li>
 *   <li>{@code decodeOnly} — read a pre-built frame in a tight loop. Reports
 *       pure "wire bytes → record" cost.</li>
 *   <li>{@code encodeDecodeRoundtrip} — sum of the two, with no pipeline
 *       framing. Useful as a sanity check: roundtrip ≈ encode + decode.</li>
 * </ul>
 *
 * <p>Signals: {@code encodeOnly} and {@code decodeOnly} should both be 0 B/op
 * after codec-generation warmup. Any non-zero allocation here is paid on every
 * real message; start here before chasing allocations in the pipeline.
 * A large asymmetry (e.g. decode 2x slower than encode) points at the
 * generated codec's {@code invokeExact} vs reflective constructor path.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(value = 1, jvmArgs = {"--enable-preview"})
public class CodecMicroBenchmark {

    public record PositionUpdate(float x, float y, float z, float yaw) {}

    private CodecRegistry codec;
    private ByteBuffer encodeBuf;
    private ByteBuffer decodeBuf;
    private WriteBuffer wb;
    private PositionUpdate msg;
    // Prebuilt encoded bytes for decodeOnly — copied into decodeBuf on each
    // call (not through the pipeline, so no framing/length prefix, just the
    // codec's [type_id, fields] layout).
    private byte[] prebuilt;

    @Setup(Level.Trial)
    public void setup() {
        codec = new CodecRegistry();
        codec.register(PositionUpdate.class);
        encodeBuf = ByteBuffer.allocate(256);
        decodeBuf = ByteBuffer.allocate(256);
        wb = new WriteBuffer(encodeBuf);
        msg = new PositionUpdate(1f, 2f, 3f, 0.5f);

        // Build a one-shot encoded byte array we can memcpy into decodeBuf on
        // every decode iteration without re-running the encode path.
        encodeBuf.clear();
        codec.encode(msg, wb);
        encodeBuf.flip();
        prebuilt = new byte[encodeBuf.remaining()];
        encodeBuf.get(prebuilt);
    }

    /** Encode a freshly-constructed record into a reused ByteBuffer. */
    @Benchmark
    public void encodeOnly(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(new PositionUpdate(1f, 2f, 3f, 0.5f), wb);
        bh.consume(encodeBuf.position());
    }

    /** Encode a pre-created record field (no per-op record allocation). */
    @Benchmark
    public void encodeOnly_cachedRecord(Blackhole bh) {
        encodeBuf.clear();
        codec.encode(msg, wb);
        bh.consume(encodeBuf.position());
    }

    /** Decode a prebuilt frame in a tight loop — isolates codec decode cost. */
    @Benchmark
    public Object decodeOnly() {
        decodeBuf.clear();
        decodeBuf.put(prebuilt);
        decodeBuf.flip();
        return codec.decode(new ReadBuffer(decodeBuf));
    }

    /** encode → decode in-memory, no pipeline, no network. */
    @Benchmark
    public Object encodeDecodeRoundtrip() {
        encodeBuf.clear();
        codec.encode(msg, wb);
        encodeBuf.flip();
        return codec.decode(new ReadBuffer(encodeBuf));
    }
}
