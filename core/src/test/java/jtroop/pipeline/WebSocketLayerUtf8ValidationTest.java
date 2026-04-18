package jtroop.pipeline;

import jtroop.ProtocolException;
import jtroop.pipeline.layers.WebSocketLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC 6455 §5.6 requires text-frame payloads to be valid UTF-8. The minimal
 * WebSocketLayer previously forwarded any bytes as-is for opcode 0x1, allowing
 * malformed UTF-8 to reach downstream handlers.
 *
 * <p>This suite covers the full classification table from RFC 3629: overlong
 * sequences, surrogate halves encoded in UTF-8, out-of-range start bytes,
 * lone continuation bytes, and truncated multi-byte sequences. Plus positive
 * tests at every code-point-length boundary.
 */
class WebSocketLayerUtf8ValidationTest {

    private static ByteBuffer buildMaskedTextFrame(byte[] payload) {
        int len = payload.length;
        if (len > 0xFFFF) throw new IllegalArgumentException("test helper only handles up to 65 535 bytes");
        // Header bytes: FIN=1 + opcode=0x1 ; MASK=1 + len encoding.
        int headerLen = (len < 126) ? 2 : 4;
        var buf = ByteBuffer.allocate(headerLen + 4 + len);
        buf.put((byte) 0x81); // FIN + text
        if (len < 126) {
            buf.put((byte) (0x80 | len));
        } else {
            buf.put((byte) (0x80 | 126));
            buf.put((byte) ((len >> 8) & 0xFF));
            buf.put((byte) (len & 0xFF));
        }
        byte[] mask = {(byte) 0xAA, (byte) 0x55, (byte) 0x33, (byte) 0xCC};
        buf.put(mask);
        for (int i = 0; i < len; i++) {
            buf.put((byte) (payload[i] ^ mask[i & 3]));
        }
        buf.flip();
        return buf;
    }

    private static void assertDecodesSuccessfully(byte[] utf8Payload) {
        var layer = new WebSocketLayer(WebSocketLayer.Role.SERVER);
        var frame = buildMaskedTextFrame(utf8Payload);
        var decoded = layer.decodeInbound(frame);
        assertNotNull(decoded, "frame should decode");
        assertEquals(utf8Payload.length, decoded.remaining(), "decoded length mismatch");
        for (int i = 0; i < utf8Payload.length; i++) {
            assertEquals(utf8Payload[i], decoded.get(i),
                    "byte " + i + " mismatch");
        }
    }

    private static void assertRejectsInvalidUtf8(byte[] payload, String hint) {
        var layer = new WebSocketLayer(WebSocketLayer.Role.SERVER);
        var frame = buildMaskedTextFrame(payload);
        var ex = assertThrows(ProtocolException.class,
                () -> layer.decodeInbound(frame),
                "should reject: " + hint);
        assertTrue(ex.getMessage().toLowerCase().contains("utf"),
                "exception should mention UTF-8: " + ex.getMessage());
    }

    // ── Positive: every code-point length boundary ────────────────

    @Test void accepts_empty() { assertDecodesSuccessfully(new byte[0]); }

    @Test void accepts_ascii_zero() { assertDecodesSuccessfully(new byte[]{0x00}); }

    @Test void accepts_ascii_max() { assertDecodesSuccessfully(new byte[]{0x7F}); }

    @Test void accepts_2byte_min() { assertDecodesSuccessfully(new byte[]{(byte)0xC2, (byte)0x80}); } // U+0080

    @Test void accepts_2byte_max() { assertDecodesSuccessfully(new byte[]{(byte)0xDF, (byte)0xBF}); } // U+07FF

    @Test void accepts_3byte_min() { assertDecodesSuccessfully(new byte[]{(byte)0xE0, (byte)0xA0, (byte)0x80}); } // U+0800

    @Test void accepts_3byte_max() { assertDecodesSuccessfully(new byte[]{(byte)0xEF, (byte)0xBF, (byte)0xBF}); } // U+FFFF

    @Test void accepts_4byte_min() { // U+10000
        assertDecodesSuccessfully(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x80, (byte)0x80});
    }

    @Test void accepts_4byte_max() { // U+10FFFF
        assertDecodesSuccessfully(new byte[]{(byte)0xF4, (byte)0x8F, (byte)0xBF, (byte)0xBF});
    }

    @Test void accepts_mixed_ascii_and_multibyte() {
        // "a" + U+00E9 + U+4E2D + U+1F600
        assertDecodesSuccessfully(new byte[]{
                0x61,
                (byte)0xC3, (byte)0xA9,
                (byte)0xE4, (byte)0xB8, (byte)0xAD,
                (byte)0xF0, (byte)0x9F, (byte)0x98, (byte)0x80,
        });
    }

    // ── Negative: malformed UTF-8 per RFC 3629 ────────────────────

    @Test void rejects_overlong_2byte_slash() {
        // 0xC0 0xAF → overlong encoding of '/' (should be 0x2F)
        assertRejectsInvalidUtf8(new byte[]{(byte)0xC0, (byte)0xAF}, "overlong 2-byte '/'");
    }

    @Test void rejects_overlong_3byte_zero() {
        // 0xE0 0x80 0x80 → overlong encoding of U+0000
        assertRejectsInvalidUtf8(new byte[]{(byte)0xE0, (byte)0x80, (byte)0x80}, "overlong 3-byte NUL");
    }

    @Test void rejects_overlong_3byte_a7f() {
        // 0xE0 0x82 0xAF → overlong encoding of '/' (should be ASCII 0x2F)
        assertRejectsInvalidUtf8(new byte[]{(byte)0xE0, (byte)0x82, (byte)0xAF}, "overlong 3-byte '/'");
    }

    @Test void rejects_overlong_4byte_zero() {
        // 0xF0 0x80 0x80 0x80 → overlong encoding of U+0000
        assertRejectsInvalidUtf8(new byte[]{(byte)0xF0, (byte)0x80, (byte)0x80, (byte)0x80}, "overlong 4-byte NUL");
    }

    @Test void rejects_surrogate_d800() {
        // 0xED 0xA0 0x80 → encodes U+D800 (high surrogate); forbidden in UTF-8.
        assertRejectsInvalidUtf8(new byte[]{(byte)0xED, (byte)0xA0, (byte)0x80}, "surrogate U+D800");
    }

    @Test void rejects_surrogate_dfff() {
        // 0xED 0xBF 0xBF → U+DFFF (low surrogate)
        assertRejectsInvalidUtf8(new byte[]{(byte)0xED, (byte)0xBF, (byte)0xBF}, "surrogate U+DFFF");
    }

    @Test void rejects_code_point_above_10ffff() {
        // 0xF4 0x90 0x80 0x80 → U+110000 (> U+10FFFF max)
        assertRejectsInvalidUtf8(new byte[]{(byte)0xF4, (byte)0x90, (byte)0x80, (byte)0x80}, "U+110000");
    }

    @Test void rejects_start_byte_f5() {
        // 0xF5 is not a valid UTF-8 start byte
        assertRejectsInvalidUtf8(new byte[]{(byte)0xF5, (byte)0x80, (byte)0x80, (byte)0x80}, "start byte 0xF5");
    }

    @Test void rejects_lone_continuation_byte() {
        // 0x80 with no preceding lead byte
        assertRejectsInvalidUtf8(new byte[]{(byte)0x80}, "lone continuation");
    }

    @Test void rejects_truncated_2byte() {
        // 0xC2 with missing continuation
        assertRejectsInvalidUtf8(new byte[]{(byte)0xC2}, "truncated 2-byte");
    }

    @Test void rejects_truncated_3byte() {
        // 0xE0 0xA0 with missing third byte
        assertRejectsInvalidUtf8(new byte[]{(byte)0xE0, (byte)0xA0}, "truncated 3-byte");
    }

    @Test void rejects_truncated_4byte() {
        // 0xF0 0x90 0x80 with missing fourth byte
        assertRejectsInvalidUtf8(new byte[]{(byte)0xF0, (byte)0x90, (byte)0x80}, "truncated 4-byte");
    }

    @Test void rejects_invalid_continuation_after_2byte_lead() {
        // 0xC2 0x00 — second byte is not in 0x80..0xBF
        assertRejectsInvalidUtf8(new byte[]{(byte)0xC2, (byte)0x00}, "invalid continuation after 2-byte lead");
    }

    @Test void rejects_invalid_continuation_after_3byte_lead() {
        // 0xE1 0x80 0x00 — third byte not in continuation range
        assertRejectsInvalidUtf8(new byte[]{(byte)0xE1, (byte)0x80, (byte)0x00}, "invalid third byte");
    }

    @Test void rejects_start_byte_c1() {
        // 0xC1 would only encode overlongs (< U+0080); reject the start byte.
        assertRejectsInvalidUtf8(new byte[]{(byte)0xC1, (byte)0x80}, "start byte 0xC1");
    }
}
