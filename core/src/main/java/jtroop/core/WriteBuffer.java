package jtroop.core;

import java.nio.ByteBuffer;

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
        writeUtf8(buf, value);
    }

    public void writeCharSequence(CharSequence value) {
        writeUtf8CharSequence(buf, value);
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

    /**
     * Writes a length-prefixed UTF-8 string directly into the ByteBuffer.
     *
     * Allocation-free: does not call {@code String.getBytes(UTF_8)} (which
     * allocates a fresh {@code byte[]} per call) and does not use
     * {@code CharsetEncoder} (which also allocates).
     *
     * Wire format: u16 byte-length prefix followed by UTF-8 bytes.
     * Max byte length: 65535.
     *
     * Two code paths:
     *  - Heap-backed ByteBuffer (the common case in this codebase):
     *    writes straight into {@code buf.array()}, skipping the per-byte
     *    bounds check / position update performed by {@code ByteBuffer.put}.
     *  - Direct/read-only ByteBuffer: falls back to per-byte {@code put}.
     */
    public static void writeUtf8(ByteBuffer buf, String value) {
        int lenPos = buf.position();
        int startPos = lenPos + 2;
        buf.position(startPos);

        int n = value.length();
        if (buf.hasArray()) {
            byte[] arr = buf.array();
            int off = buf.arrayOffset() + startPos;
            int w = 0;
            int i = 0;
            // Fast path: pure-ASCII run. Most chat/handshake payloads stay in
            // this branch — one iastore per char, no ByteBuffer.put bookkeeping.
            while (i < n) {
                char c = value.charAt(i);
                if (c < 0x80) {
                    arr[off + w++] = (byte) c;
                    i++;
                } else if (c < 0x800) {
                    arr[off + w++] = (byte) (0xC0 | (c >> 6));
                    arr[off + w++] = (byte) (0x80 | (c & 0x3F));
                    i++;
                } else if (Character.isHighSurrogate(c) && i + 1 < n
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, value.charAt(i + 1));
                    arr[off + w++] = (byte) (0xF0 | (cp >> 18));
                    arr[off + w++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    arr[off + w++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    arr[off + w++] = (byte) (0x80 | (cp & 0x3F));
                    i += 2;
                } else {
                    // Single-char BMP, including unpaired surrogates.
                    arr[off + w++] = (byte) (0xE0 | (c >> 12));
                    arr[off + w++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    arr[off + w++] = (byte) (0x80 | (c & 0x3F));
                    i++;
                }
            }
            buf.position(startPos + w);
            // Backpatch length prefix.
            arr[buf.arrayOffset() + lenPos]     = (byte) ((w >> 8) & 0xFF);
            arr[buf.arrayOffset() + lenPos + 1] = (byte) (w & 0xFF);
        } else {
            int i = 0;
            while (i < n) {
                char c = value.charAt(i);
                if (c < 0x80) {
                    buf.put((byte) c);
                    i++;
                } else if (c < 0x800) {
                    buf.put((byte) (0xC0 | (c >> 6)));
                    buf.put((byte) (0x80 | (c & 0x3F)));
                    i++;
                } else if (Character.isHighSurrogate(c) && i + 1 < n
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, value.charAt(i + 1));
                    buf.put((byte) (0xF0 | (cp >> 18)));
                    buf.put((byte) (0x80 | ((cp >> 12) & 0x3F)));
                    buf.put((byte) (0x80 | ((cp >> 6) & 0x3F)));
                    buf.put((byte) (0x80 | (cp & 0x3F)));
                    i += 2;
                } else {
                    buf.put((byte) (0xE0 | (c >> 12)));
                    buf.put((byte) (0x80 | ((c >> 6) & 0x3F)));
                    buf.put((byte) (0x80 | (c & 0x3F)));
                    i++;
                }
            }
            int byteLen = buf.position() - startPos;
            buf.putShort(lenPos, (short) byteLen);
        }
    }

    /**
     * Writes a length-prefixed UTF-8 {@link CharSequence} directly into the ByteBuffer.
     * If the value is a {@link String}, delegates to {@link #writeUtf8(ByteBuffer, String)}.
     * If it is a {@link BufferCharSequence}, copies the raw UTF-8 bytes directly — zero
     * decode/re-encode overhead.
     * Otherwise, iterates chars and encodes UTF-8 inline.
     */
    public static void writeUtf8CharSequence(ByteBuffer buf, CharSequence value) {
        if (value instanceof String s) {
            writeUtf8(buf, s);
            return;
        }
        if (value instanceof BufferCharSequence bcs) {
            // Fast path: copy raw UTF-8 bytes directly — no char decode needed.
            buf.putShort((short) bcs.byteLength());
            // BufferCharSequence wraps a byte[] region; copy it out.
            buf.put(bcs.backingArray(), bcs.backingOffset(), bcs.byteLength());
            return;
        }
        // Generic CharSequence path: encode char-by-char
        int lenPos = buf.position();
        int startPos = lenPos + 2;
        buf.position(startPos);
        int n = value.length();
        int i = 0;
        while (i < n) {
            char c = value.charAt(i);
            if (c < 0x80) {
                buf.put((byte) c);
                i++;
            } else if (c < 0x800) {
                buf.put((byte) (0xC0 | (c >> 6)));
                buf.put((byte) (0x80 | (c & 0x3F)));
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, value.charAt(i + 1));
                buf.put((byte) (0xF0 | (cp >> 18)));
                buf.put((byte) (0x80 | ((cp >> 12) & 0x3F)));
                buf.put((byte) (0x80 | ((cp >> 6) & 0x3F)));
                buf.put((byte) (0x80 | (cp & 0x3F)));
                i += 2;
            } else {
                buf.put((byte) (0xE0 | (c >> 12)));
                buf.put((byte) (0x80 | ((c >> 6) & 0x3F)));
                buf.put((byte) (0x80 | (c & 0x3F)));
                i++;
            }
        }
        int byteLen = buf.position() - startPos;
        buf.putShort(lenPos, (short) byteLen);
    }
}
