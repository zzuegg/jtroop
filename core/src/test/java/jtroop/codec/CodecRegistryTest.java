package jtroop.codec;

import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CodecRegistryTest {

    record SimpleMessage(int x, int y) {}
    record FloatMessage(float a, float b, float c) {}
    record MixedMessage(int id, float value, long timestamp) {}
    record StringMessage(String text, int code) {}
    record EmptyMessage() {}

    @Test
    void register_assignsUniqueTypeIds() {
        var registry = new CodecRegistry();
        registry.register(SimpleMessage.class);
        registry.register(FloatMessage.class);

        int id1 = registry.typeId(SimpleMessage.class);
        int id2 = registry.typeId(FloatMessage.class);

        assertNotEquals(id1, id2);
        assertTrue(id1 >= 0 && id1 <= 0xFFFF);
        assertTrue(id2 >= 0 && id2 <= 0xFFFF);
    }

    @Test
    void typeId_throwsForUnregisteredType() {
        var registry = new CodecRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.typeId(SimpleMessage.class));
    }

    @Test
    void encode_decode_simpleMessage() {
        var registry = new CodecRegistry();
        registry.register(SimpleMessage.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new SimpleMessage(10, 20);
        registry.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = registry.decode(new ReadBuffer(buf));

        assertEquals(msg, decoded);
    }

    @Test
    void encode_decode_floatMessage() {
        var registry = new CodecRegistry();
        registry.register(FloatMessage.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new FloatMessage(1.0f, 2.5f, -3.14f);
        registry.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = registry.decode(new ReadBuffer(buf));

        assertEquals(msg, decoded);
    }

    @Test
    void encode_decode_mixedMessage() {
        var registry = new CodecRegistry();
        registry.register(MixedMessage.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new MixedMessage(42, 9.81f, System.nanoTime());
        registry.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = registry.decode(new ReadBuffer(buf));

        assertEquals(msg, decoded);
    }

    @Test
    void encode_decode_stringMessage() {
        var registry = new CodecRegistry();
        registry.register(StringMessage.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new StringMessage("hello world", 200);
        registry.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = registry.decode(new ReadBuffer(buf));

        assertEquals(msg, decoded);
    }

    @Test
    void encode_decode_emptyMessage() {
        var registry = new CodecRegistry();
        registry.register(EmptyMessage.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new EmptyMessage();
        registry.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = registry.decode(new ReadBuffer(buf));

        assertEquals(msg, decoded);
    }

    @Test
    void encode_includesTypeIdPrefix() {
        var registry = new CodecRegistry();
        registry.register(SimpleMessage.class);
        int expectedId = registry.typeId(SimpleMessage.class);

        var buf = ByteBuffer.allocate(256);
        registry.encode(new SimpleMessage(1, 2), new WriteBuffer(buf));
        buf.flip();

        // first 2 bytes should be the type id
        int typeId = buf.getShort() & 0xFFFF;
        assertEquals(expectedId, typeId);
    }

    @Test
    void decode_multipeDifferentTypes() {
        var registry = new CodecRegistry();
        registry.register(SimpleMessage.class);
        registry.register(FloatMessage.class);

        var buf = ByteBuffer.allocate(512);
        var wb = new WriteBuffer(buf);
        var msg1 = new SimpleMessage(1, 2);
        var msg2 = new FloatMessage(3.0f, 4.0f, 5.0f);

        registry.encode(msg1, wb);
        registry.encode(msg2, wb);
        buf.flip();

        var rb = new ReadBuffer(buf);
        assertEquals(msg1, registry.decode(rb));
        assertEquals(msg2, registry.decode(rb));
    }

    @Test
    void classForTypeId_returnsRegisteredClass() {
        var registry = new CodecRegistry();
        registry.register(SimpleMessage.class);
        int id = registry.typeId(SimpleMessage.class);
        assertEquals(SimpleMessage.class, registry.classForTypeId(id));
    }
}
