package jtroop.core;

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
        return readUtf8(buf);
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

    // Per-thread scratch for the direct-buffer decode path: used only between
    // `buf.get(scratch, ...)` and the `new String(scratch, ...)` call; never
    // escapes. Heap-backed buffers skip this entirely and hand `buf.array()`
    // directly to the String constructor.
    private static final ThreadLocal<byte[]> SCRATCH =
            ThreadLocal.withInitial(() -> new byte[256]);

    /**
     * Reads a length-prefixed UTF-8 string. The returned {@code String} and its
     * internal backing array are the only unavoidable allocations:
     *  - Heap-backed ByteBuffer: hands {@code buf.array()} straight to the
     *    {@code String} constructor — no intermediate byte[] copy.
     *  - Direct ByteBuffer: copies into a per-thread scratch byte[] that is
     *    grown lazily, then hands that scratch to the {@code String}
     *    constructor. The scratch is reused across calls.
     */
    public static String readUtf8(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) return "";
        if (buf.hasArray()) {
            byte[] arr = buf.array();
            int off = buf.arrayOffset() + buf.position();
            buf.position(buf.position() + len);
            return new String(arr, off, len, StandardCharsets.UTF_8);
        }
        byte[] scratch = SCRATCH.get();
        if (scratch.length < len) {
            // Grow to next power of two >= len. Rare path; afterwards reused.
            int newCap = Integer.highestOneBit(len - 1) << 1;
            scratch = new byte[newCap];
            SCRATCH.set(scratch);
        }
        buf.get(scratch, 0, len);
        return new String(scratch, 0, len, StandardCharsets.UTF_8);
    }
}
