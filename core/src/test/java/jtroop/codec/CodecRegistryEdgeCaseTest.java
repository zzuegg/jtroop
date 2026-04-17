package jtroop.codec;

import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path and edge-case tests for {@link CodecRegistry}.
 * Covers: unsupported types, unknown type ids, corrupt wire data,
 * empty-string fields, boolean fields, auto-registration, and
 * pre-resolved encoder handles.
 */
class CodecRegistryEdgeCaseTest {

    record BoolMsg(boolean flag, int value) {}
    record AllPrimitives(byte b, short s, int i, long l, float f, double d, boolean z) {}
    record EmptyStringMsg(String text) {}
    record TwoStrings(String a, String b) {}

    // --- Unsupported component type ---

    record BadMsg(Object unsupported) {}

    @Test
    void register_unsupportedComponentType_throws() {
        var reg = new CodecRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.register(BadMsg.class));
    }

    // --- Unknown type id on decode ---

    @Test
    void decode_unknownTypeId_throws() {
        var reg = new CodecRegistry();
        var buf = ByteBuffer.allocate(16);
        buf.putShort((short) 0x7FFF); // unregistered type id
        buf.flip();
        assertThrows(IllegalArgumentException.class, () -> reg.decode(new ReadBuffer(buf)));
    }

    // --- classForTypeId with unknown id ---

    @Test
    void classForTypeId_unknownId_throws() {
        var reg = new CodecRegistry();
        assertThrows(IllegalArgumentException.class, () -> reg.classForTypeId(0x7FFF));
    }

    // --- Boolean field round-trip ---

    @Test
    void encodeDecode_booleanField() {
        var reg = new CodecRegistry();
        reg.register(BoolMsg.class);

        for (boolean flag : new boolean[]{true, false}) {
            var buf = ByteBuffer.allocate(64);
            var msg = new BoolMsg(flag, 42);
            reg.encode(msg, new WriteBuffer(buf));
            buf.flip();
            assertEquals(msg, reg.decode(new ReadBuffer(buf)));
        }
    }

    // --- All primitive types in one record ---

    @Test
    void encodeDecode_allPrimitiveTypes() {
        var reg = new CodecRegistry();
        reg.register(AllPrimitives.class);

        var msg = new AllPrimitives((byte) 0x7F, (short) -1, Integer.MAX_VALUE,
                Long.MIN_VALUE, Float.NaN, Double.POSITIVE_INFINITY, true);
        var buf = ByteBuffer.allocate(256);
        reg.encode(msg, new WriteBuffer(buf));
        buf.flip();
        assertEquals(msg, reg.decode(new ReadBuffer(buf)));
    }

    // --- Empty string field ---

    @Test
    void encodeDecode_emptyString() {
        var reg = new CodecRegistry();
        reg.register(EmptyStringMsg.class);

        var msg = new EmptyStringMsg("");
        var buf = ByteBuffer.allocate(64);
        reg.encode(msg, new WriteBuffer(buf));
        buf.flip();
        assertEquals(msg, reg.decode(new ReadBuffer(buf)));
    }

    // --- Auto-registration on encode ---

    @Test
    void encode_autoRegisters_unregisteredType() {
        var reg = new CodecRegistry();
        // Do NOT call register — encode should auto-register
        var buf = ByteBuffer.allocate(256);
        var msg = new BoolMsg(true, 99);
        assertDoesNotThrow(() -> reg.encode(msg, new WriteBuffer(buf)));
        buf.flip();
        assertEquals(msg, reg.decode(new ReadBuffer(buf)));
    }

    // --- Double registration is idempotent ---

    @Test
    void register_twice_isIdempotent() {
        var reg = new CodecRegistry();
        reg.register(BoolMsg.class);
        int id1 = reg.typeId(BoolMsg.class);
        reg.register(BoolMsg.class);
        int id2 = reg.typeId(BoolMsg.class);
        assertEquals(id1, id2);
    }

    // --- Truncated wire data causes exception, not silent corruption ---

    @Test
    void decode_truncatedPayload_throwsBufferUnderflow() {
        var reg = new CodecRegistry();
        reg.register(AllPrimitives.class);
        int typeId = reg.typeId(AllPrimitives.class);

        // Write only the type id + a few bytes (not enough for all fields)
        var buf = ByteBuffer.allocate(8);
        buf.putShort((short) typeId);
        buf.putInt(42); // only 4 bytes, but AllPrimitives needs much more
        buf.flip();

        assertThrows(Exception.class, () -> reg.decode(new ReadBuffer(buf)));
    }

    // --- EncoderHandle ---

    @Test
    void resolveEncoder_fixedSize_primitiveOnly() {
        var reg = new CodecRegistry();
        reg.register(BoolMsg.class);
        var handle = reg.resolveEncoder(BoolMsg.class);
        assertTrue(handle.isFixedSize());
        // BoolMsg: 2 (typeId) + 1 (boolean) + 4 (int) = 7
        assertEquals(7, handle.fixedPayloadSize());
    }

    @Test
    void resolveEncoder_variableSize_withString() {
        var reg = new CodecRegistry();
        reg.register(EmptyStringMsg.class);
        var handle = reg.resolveEncoder(EmptyStringMsg.class);
        assertFalse(handle.isFixedSize());
    }

    @Test
    void resolveEncoder_encode_matchesRegistryEncode() {
        var reg = new CodecRegistry();
        reg.register(BoolMsg.class);
        var handle = reg.resolveEncoder(BoolMsg.class);
        var msg = new BoolMsg(true, 7);

        var buf1 = ByteBuffer.allocate(64);
        handle.encode(msg, buf1);
        buf1.flip();

        var buf2 = ByteBuffer.allocate(64);
        reg.encode(msg, buf2);
        buf2.flip();

        assertEquals(buf1, buf2);
    }

    // --- Multiple strings to exercise length-prefix handling ---

    @Test
    void encodeDecode_twoStrings() {
        var reg = new CodecRegistry();
        reg.register(TwoStrings.class);

        var msg = new TwoStrings("hello", "world");
        var buf = ByteBuffer.allocate(256);
        reg.encode(msg, new WriteBuffer(buf));
        buf.flip();
        assertEquals(msg, reg.decode(new ReadBuffer(buf)));
    }

    @Test
    void encodeDecode_unicodeStrings() {
        var reg = new CodecRegistry();
        reg.register(TwoStrings.class);

        var msg = new TwoStrings("\u00E9\u20AC", "\uD83D\uDE00");
        var buf = ByteBuffer.allocate(256);
        reg.encode(msg, new WriteBuffer(buf));
        buf.flip();
        assertEquals(msg, reg.decode(new ReadBuffer(buf)));
    }
}
