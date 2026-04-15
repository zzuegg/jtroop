package net.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class WriteBuffer {

    private final ByteBuffer buf;

    public WriteBuffer(ByteBuffer buf) {
        this.buf = buf;
    }

    public void writeInt(int value) {
        buf.putInt(value);
    }

    public void writeFloat(float value) {
        buf.putFloat(value);
    }

    public void writeLong(long value) {
        buf.putLong(value);
    }

    public void writeDouble(double value) {
        buf.putDouble(value);
    }

    public void writeByte(byte value) {
        buf.put(value);
    }

    public void writeShort(short value) {
        buf.putShort(value);
    }

    public void writeString(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) bytes.length);
        buf.put(bytes);
    }

    public void writeBytes(byte[] bytes) {
        buf.put(bytes);
    }

    public int position() {
        return buf.position();
    }

    public ByteBuffer buffer() {
        return buf;
    }
}
