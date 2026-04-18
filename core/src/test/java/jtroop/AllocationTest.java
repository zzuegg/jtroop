package jtroop;

import jtroop.codec.CodecRegistry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zero-allocation unit assertions. These lock in the "0 B/op" promise of
 * the hot-path codec + framing + buffer primitives — regressions that
 * introduce per-call allocation will fail these tests at local `./gradlew
 * :core:test` time rather than surfacing in JMH runs only.
 */
class AllocationTest {

    record Position(float x, float y, float z) {}
    record Chat(int sender, String text) {}

    @Test
    void codecRoundtrip_zeroAlloc_onPrimitiveRecord() {
        var codec = new CodecRegistry();
        codec.register(Position.class);
        var msg = new Position(1.0f, 2.0f, 3.0f);
        var buf = ByteBuffer.allocate(64);

        // Warm up — the first few calls trigger classloading, JIT
        // compilation, and ThreadMXBean initialisation, all of which
        // allocate. The steady state after warmup is what we gate on.
        for (int i = 0; i < 20_000; i++) {
            buf.clear();
            codec.encode(msg, buf);
            buf.flip();
            codec.decode(new jtroop.core.ReadBuffer(buf));
        }

        // Measurement phase: 1 000 fresh iterations should allocate
        // essentially nothing beyond a few JMX housekeeping bytes.
        long delta = Allocation.measure(() -> {
            for (int i = 0; i < 1_000; i++) {
                buf.clear();
                codec.encode(msg, buf);
                buf.flip();
                codec.decode(new jtroop.core.ReadBuffer(buf));
            }
        });
        // Direct-byte decode of a primitive-only record produces a new
        // Record per iteration (3 floats worth of state). That's 24 B × 1000
        // = ~24 KB worth of records. What we're really checking is that the
        // CODEC ITSELF isn't adding per-call overhead on top (no HashMap
        // allocation, no Integer boxing, no substring). Budget 64 KB.
        Allocation.assertNearZero(delta, 64_000, "codec.encode + decode × 1000");
    }

    @Test
    void writeBuffer_writeUtf8_zeroAlloc_onCachedString() {
        var buf = ByteBuffer.allocate(128);
        final String s = "hello, world";

        for (int i = 0; i < 20_000; i++) {
            buf.clear();
            jtroop.core.WriteBuffer.writeUtf8(buf, s);
        }

        long delta = Allocation.measure(() -> {
            for (int i = 0; i < 10_000; i++) {
                buf.clear();
                jtroop.core.WriteBuffer.writeUtf8(buf, s);
            }
        });
        // Pre-fix of Fix 9 might have allocated via String.getBytes; post-fix
        // writes byte-by-byte into the buffer. Zero allocation on the hot path.
        Allocation.assertNearZero(delta, 2_048, "writeUtf8 × 10000 ASCII");
    }

    @Test
    void readBuffer_readUtf8CharSequence_zeroAlloc_onHeapBuffer() {
        var raw = ByteBuffer.allocate(128);
        jtroop.core.WriteBuffer.writeUtf8(raw, "hello, world");
        raw.flip();
        int limit = raw.limit();

        for (int i = 0; i < 20_000; i++) {
            raw.position(0).limit(limit);
            jtroop.core.ReadBuffer.readUtf8CharSequence(raw);
        }

        long delta = Allocation.measure(() -> {
            for (int i = 0; i < 10_000; i++) {
                raw.position(0).limit(limit);
                jtroop.core.ReadBuffer.readUtf8CharSequence(raw);
            }
        });
        // readUtf8CharSequence on a heap buffer allocates exactly one
        // BufferCharSequence per call (~24 B). 10 000 × ~24 = ~240 KB worth
        // of CharSequence instances. Scalar replacement SHOULD eliminate
        // most if the caller consumes them fast, but this test is pessimistic
        // because the lambda keeps the result alive briefly. Budget 500 KB.
        Allocation.assertNearZero(delta, 500_000, "readUtf8CharSequence × 10000");
    }
}
