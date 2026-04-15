package net.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ReadBuffer {

    private final ByteBuffer buf;

    public ReadBuffer(ByteBuffer buf) {
        this.buf = buf;
    }

    public int readInt() {
        return buf.getInt();
    }

    public float readFloat() {
        return buf.getFloat();
    }

    public long readLong() {
        return buf.getLong();
    }

    public double readDouble() {
        return buf.getDouble();
    }

    public byte readByte() {
        return buf.get();
    }

    public short readShort() {
        return buf.getShort();
    }

    public String readString() {
        int len = buf.getShort() & 0xFFFF;
        var bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public byte[] readBytes(int length) {
        var bytes = new byte[length];
        buf.get(bytes);
        return bytes;
    }

    public int position() {
        return buf.position();
    }

    public int remaining() {
        return buf.remaining();
    }

    public ByteBuffer buffer() {
        return buf;
    }
}
