package jtroop.pipeline;

import jtroop.ProtocolException;
import jtroop.pipeline.LayerContext;
import jtroop.pipeline.layers.CompressionLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decompression-bomb defense. Pre-fix, {@link CompressionLayer#decodeInbound}
 * trusted the 4-byte {@code originalSize} on the wire unconditionally and
 * allocated a {@code byte[originalSize]} before inflating. A malicious peer
 * could announce {@code originalSize = Integer.MAX_VALUE} (or any large
 * value) in a tiny frame and force the server to attempt a ~2 GB allocation,
 * either OOMing the JVM or triggering GC collapse.
 *
 * <p>Post-fix, the layer caps {@code originalSize} at a configurable maximum
 * ({@link CompressionLayer#DEFAULT_MAX_UNCOMPRESSED} by default, configurable
 * via constructor) and rejects over-cap frames with {@link ProtocolException}
 * before any allocation.
 */
@Timeout(5)
class CompressionBombTest {

    /** Craft a malicious compressed frame that advertises a huge originalSize
     *  while carrying a tiny (legitimate) zlib payload. */
    private static ByteBuffer buildBombFrame(int advertisedOriginalSize, byte[] tinyPayload) {
        var deflater = new Deflater();
        deflater.setInput(tinyPayload);
        deflater.finish();
        var compressed = new byte[64];
        int cLen = deflater.deflate(compressed);
        deflater.end();

        var wire = ByteBuffer.allocate(1 + 4 + cLen);
        wire.put((byte) 0x01);                    // FLAG_COMPRESSED
        wire.putInt(advertisedOriginalSize);      // the lie
        wire.put(compressed, 0, cLen);
        wire.flip();
        return wire;
    }

    @Test
    void decodeInbound_bombFrame_rejectsWithoutHugeAllocation() {
        var layer = new CompressionLayer();
        try {
            var tiny = new byte[]{1, 2, 3, 4};
            // Announce 500 MB original size — well above the default cap.
            var bomb = buildBombFrame(500 * 1024 * 1024, tiny);
            assertThrows(ProtocolException.class, () -> layer.decodeInbound(bomb),
                    "decompression bomb must be rejected before allocation");
        } finally {
            layer.close();
        }
    }

    @Test
    void decodeInbound_negativeOriginalSize_rejected() {
        var layer = new CompressionLayer();
        try {
            var tiny = new byte[]{1, 2, 3, 4};
            // Negative originalSize via sign bit — pre-fix would try to allocate
            // byte[negative] and throw NegativeArraySizeException (at best) or
            // wrap around via Math.max to a small positive value (at worst).
            var bomb = buildBombFrame(-1, tiny);
            assertThrows(ProtocolException.class, () -> layer.decodeInbound(bomb));
        } finally {
            layer.close();
        }
    }

    @Test
    void decodeInbound_boundaryAtCap_accepted() {
        // Build a legitimate frame whose originalSize is exactly the cap.
        // Using the 3-arg constructor to set a small, testable cap (4 KiB).
        int cap = 4096;
        var layer = new CompressionLayer(Deflater.DEFAULT_COMPRESSION, 64, cap);
        try {
            var payload = new byte[cap];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
            var payloadBuf = ByteBuffer.wrap(payload);

            var wire = ByteBuffer.allocate(cap + 1024);
            layer.encodeOutbound(LayerContext.NOOP, payloadBuf, wire);
            wire.flip();

            var decoded = layer.decodeInbound(wire);
            assertNotNull(decoded);
            assertEquals(cap, decoded.remaining());
        } finally {
            layer.close();
        }
    }

    @Test
    void decodeInbound_oneByteOverCap_rejected() {
        int cap = 4096;
        var layer = new CompressionLayer(Deflater.DEFAULT_COMPRESSION, 64, cap);
        try {
            var tiny = new byte[]{1, 2, 3, 4};
            var bomb = buildBombFrame(cap + 1, tiny);
            assertThrows(ProtocolException.class, () -> layer.decodeInbound(bomb));
        } finally {
            layer.close();
        }
    }

    @Test
    void encodeOutbound_payloadExceedingCap_rejected() {
        // Encoding a payload larger than the cap should also fail — otherwise
        // the sender creates frames its own decoder would reject.
        int cap = 4096;
        var layer = new CompressionLayer(Deflater.DEFAULT_COMPRESSION, 64, cap);
        try {
            var tooBig = ByteBuffer.allocate(cap + 1);
            var wire = ByteBuffer.allocate(cap * 2);
            assertThrows(ProtocolException.class,
                    () -> layer.encodeOutbound(LayerContext.NOOP, tooBig, wire));
        } finally {
            layer.close();
        }
    }
}
