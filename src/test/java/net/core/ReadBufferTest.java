package net.core;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ReadBufferTest {

    private ByteBuffer prepareBuffer(java.util.function.Consumer<WriteBuffer> writer) {
        var buf = ByteBuffer.allocate(256);
        var wb = new WriteBuffer(buf);
        writer.accept(wb);
        buf.flip();
        return buf;
    }

    @Test
    void readInt_readsIntAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeInt(42));
        var rb = new ReadBuffer(buf);
        assertEquals(42, rb.readInt());
    }

    @Test
    void readFloat_readsFloatAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeFloat(3.14f));
        var rb = new ReadBuffer(buf);
        assertEquals(3.14f, rb.readFloat());
    }

    @Test
    void readLong_readsLongAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeLong(123456789L));
        var rb = new ReadBuffer(buf);
        assertEquals(123456789L, rb.readLong());
    }

    @Test
    void readDouble_readsDoubleAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeDouble(2.718281828));
        var rb = new ReadBuffer(buf);
        assertEquals(2.718281828, rb.readDouble());
    }

    @Test
    void readByte_readsByteAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeByte((byte) 0xAB));
        var rb = new ReadBuffer(buf);
        assertEquals((byte) 0xAB, rb.readByte());
    }

    @Test
    void readShort_readsShortAtCurrentPosition() {
        var buf = prepareBuffer(wb -> wb.writeShort((short) 1234));
        var rb = new ReadBuffer(buf);
        assertEquals((short) 1234, rb.readShort());
    }

    @Test
    void readString_readsLengthPrefixedUtf8() {
        var buf = prepareBuffer(wb -> wb.writeString("hello"));
        var rb = new ReadBuffer(buf);
        assertEquals("hello", rb.readString());
    }

    @Test
    void multipleReads_sequentialPositions() {
        var buf = prepareBuffer(wb -> {
            wb.writeInt(1);
            wb.writeFloat(2.0f);
            wb.writeLong(3L);
        });
        var rb = new ReadBuffer(buf);
        assertEquals(1, rb.readInt());
        assertEquals(2.0f, rb.readFloat());
        assertEquals(3L, rb.readLong());
    }

    @Test
    void position_returnsCurrentReadPosition() {
        var buf = prepareBuffer(wb -> { wb.writeInt(1); wb.writeLong(2L); });
        var rb = new ReadBuffer(buf);
        assertEquals(0, rb.position());
        rb.readInt();
        assertEquals(4, rb.position());
        rb.readLong();
        assertEquals(12, rb.position());
    }

    @Test
    void remaining_returnsUnreadBytes() {
        var buf = prepareBuffer(wb -> { wb.writeInt(1); wb.writeInt(2); });
        var rb = new ReadBuffer(buf);
        assertEquals(8, rb.remaining());
        rb.readInt();
        assertEquals(4, rb.remaining());
    }

    @Test
    void readBytes_readsRawByteArray() {
        var buf = prepareBuffer(wb -> wb.writeBytes(new byte[]{1, 2, 3, 4}));
        var rb = new ReadBuffer(buf);
        var result = rb.readBytes(4);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result);
    }
}
