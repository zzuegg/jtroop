package net.core;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class WriteBufferTest {

    @Test
    void writeInt_putsIntAtCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeInt(42);
        buf.flip();
        assertEquals(42, buf.getInt());
    }

    @Test
    void writeFloat_putsFloatAtCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeFloat(3.14f);
        buf.flip();
        assertEquals(3.14f, buf.getFloat());
    }

    @Test
    void writeLong_putsLongAtCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeLong(123456789L);
        buf.flip();
        assertEquals(123456789L, buf.getLong());
    }

    @Test
    void writeDouble_putsDoubleAtCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeDouble(2.718281828);
        buf.flip();
        assertEquals(2.718281828, buf.getDouble());
    }

    @Test
    void writeByte_putsByteAtCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeByte((byte) 0xFF);
        buf.flip();
        assertEquals((byte) 0xFF, buf.get());
    }

    @Test
    void writeShort_putsShortatCurrentPosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeShort((short) 1234);
        buf.flip();
        assertEquals((short) 1234, buf.getShort());
    }

    @Test
    void writeString_putsLengthPrefixedUtf8() {
        var buf = ByteBuffer.allocate(128);
        var wb = new WriteBuffer(buf);
        wb.writeString("hello");
        buf.flip();
        int len = buf.getShort() & 0xFFFF;
        assertEquals(5, len);
        var bytes = new byte[len];
        buf.get(bytes);
        assertEquals("hello", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void multipleWrites_sequentialPositions() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeInt(1);
        wb.writeFloat(2.0f);
        wb.writeLong(3L);
        buf.flip();
        assertEquals(1, buf.getInt());
        assertEquals(2.0f, buf.getFloat());
        assertEquals(3L, buf.getLong());
    }

    @Test
    void position_returnsCurrentWritePosition() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        assertEquals(0, wb.position());
        wb.writeInt(1);
        assertEquals(4, wb.position());
        wb.writeLong(2L);
        assertEquals(12, wb.position());
    }

    @Test
    void writeBytes_putsRawByteArray() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeBytes(new byte[]{1, 2, 3, 4});
        buf.flip();
        assertEquals(1, buf.get());
        assertEquals(2, buf.get());
        assertEquals(3, buf.get());
        assertEquals(4, buf.get());
    }
}
