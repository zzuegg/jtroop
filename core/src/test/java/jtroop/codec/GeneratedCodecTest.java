package jtroop.codec;

import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedCodecTest {

    public record SimpleMsg(int x, int y) {}
    public record FloatMsg(float a, float b, float c) {}
    public record MixedMsg(int id, float value, long timestamp) {}
    public record StringMsg(String text, int code) {}
    public record CharSeqMsg(CharSequence text, int code) {}

    @Test
    void generatedCodec_encodeDecode_simpleMsg() {
        var codec = new CodecRegistry();
        codec.register(SimpleMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new SimpleMsg(10, 20);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = codec.decode(new ReadBuffer(buf));
        assertEquals(msg, decoded);
    }

    @Test
    void generatedCodec_encodeDecode_floatMsg() {
        var codec = new CodecRegistry();
        codec.register(FloatMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new FloatMsg(1.0f, 2.5f, -3.14f);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = codec.decode(new ReadBuffer(buf));
        assertEquals(msg, decoded);
    }

    @Test
    void generatedCodec_encodeDecode_mixedMsg() {
        var codec = new CodecRegistry();
        codec.register(MixedMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new MixedMsg(42, 9.81f, 123456789L);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = codec.decode(new ReadBuffer(buf));
        assertEquals(msg, decoded);
    }

    @Test
    void generatedCodec_encodeDecode_stringMsg() {
        var codec = new CodecRegistry();
        codec.register(StringMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new StringMsg("hello world", 200);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = codec.decode(new ReadBuffer(buf));
        assertEquals(msg, decoded);
    }

    @Test
    void generatedCodec_encodeDecode_charSequenceMsg() {
        var codec = new CodecRegistry();
        codec.register(CharSeqMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new CharSeqMsg("hello world", 200);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = (CharSeqMsg) codec.decode(new ReadBuffer(buf));
        assertEquals("hello world", decoded.text().toString());
        assertEquals(200, decoded.code());
    }

    @Test
    void generatedCodec_charSequenceMsg_usesBufferCharSequence() {
        var codec = new CodecRegistry();
        codec.register(CharSeqMsg.class);

        var buf = ByteBuffer.allocate(256);
        var msg = new CharSeqMsg("test", 1);
        codec.encode(msg, new WriteBuffer(buf));
        buf.flip();
        var decoded = (CharSeqMsg) codec.decode(new ReadBuffer(buf));
        assertInstanceOf(jtroop.core.BufferCharSequence.class, decoded.text());
    }

    @Test
    void generatedCodec_charSequenceMsg_reencodes() {
        var codec = new CodecRegistry();
        codec.register(CharSeqMsg.class);

        // Encode with String, decode to CharSequence, re-encode, re-decode
        var buf = ByteBuffer.allocate(256);
        codec.encode(new CharSeqMsg("round trip", 42), new WriteBuffer(buf));
        buf.flip();
        var first = (CharSeqMsg) codec.decode(new ReadBuffer(buf));

        buf.clear();
        codec.encode(first, new WriteBuffer(buf));
        buf.flip();
        var second = (CharSeqMsg) codec.decode(new ReadBuffer(buf));
        assertEquals("round trip", second.text().toString());
        assertEquals(42, second.code());
    }

    @Test
    void generatedCodec_multipleTypes() {
        var codec = new CodecRegistry();
        codec.register(SimpleMsg.class);
        codec.register(FloatMsg.class);

        var buf = ByteBuffer.allocate(512);
        var wb = new WriteBuffer(buf);
        codec.encode(new SimpleMsg(1, 2), wb);
        codec.encode(new FloatMsg(3f, 4f, 5f), wb);
        buf.flip();

        var rb = new ReadBuffer(buf);
        assertEquals(new SimpleMsg(1, 2), codec.decode(rb));
        assertEquals(new FloatMsg(3f, 4f, 5f), codec.decode(rb));
    }
}
