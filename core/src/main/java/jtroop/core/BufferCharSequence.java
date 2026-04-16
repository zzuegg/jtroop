package jtroop.core;

import java.nio.charset.StandardCharsets;

/**
 * A zero-allocation {@link CharSequence} backed by a UTF-8 byte region.
 *
 * <p>On decode, instead of {@code new String(bytes, UTF_8)} (which allocates both
 * a {@code String} and its internal {@code byte[]}), the codec returns this
 * wrapper pointing at the already-decoded bytes in a scratch buffer. If the
 * handler only calls {@link #length()}, {@link #charAt(int)}, or iterates chars,
 * no {@code String} is ever created. If {@link #toString()} is called (e.g. for
 * logging or storage), the {@code String} is materialized once and cached.
 *
 * <p>The backing byte array is typically a per-thread scratch that is reused
 * across decode calls. Callers must not hold a reference to this object past
 * the current message dispatch — call {@link #toString()} to snapshot if needed.
 *
 * <p>This class decodes UTF-8 on the fly for {@code charAt()} and caches the
 * full char-length lazily. For the common case of pure-ASCII payloads, each
 * byte maps 1:1 to a char, so {@code charAt(i)} is a single array load.
 */
public final class BufferCharSequence implements CharSequence {

    private final byte[] bytes;
    private final int offset;
    private final int byteLength;
    // Lazily computed char-level view. -1 means not yet computed.
    private int charLength = -1;
    // Lazily materialized String — null until toString() is called.
    private String materialized;

    public BufferCharSequence(byte[] bytes, int offset, int byteLength) {
        this.bytes = bytes;
        this.offset = offset;
        this.byteLength = byteLength;
    }

    @Override
    public int length() {
        int cl = charLength;
        if (cl >= 0) return cl;
        cl = computeCharLength();
        charLength = cl;
        return cl;
    }

    @Override
    public char charAt(int index) {
        // Fast path: scan bytes to find the char at the given index.
        // For ASCII-only strings this is O(1)-ish per call.
        int bytePos = offset;
        int end = offset + byteLength;
        int charIdx = 0;
        while (bytePos < end) {
            int b = bytes[bytePos] & 0xFF;
            if (charIdx == index) {
                if (b < 0x80) return (char) b;
                if (b < 0xE0) {
                    return (char) (((b & 0x1F) << 6) | (bytes[bytePos + 1] & 0x3F));
                }
                if (b < 0xF0) {
                    return (char) (((b & 0x0F) << 12)
                            | ((bytes[bytePos + 1] & 0x3F) << 6)
                            | (bytes[bytePos + 2] & 0x3F));
                }
                // 4-byte sequence → surrogate pair, return high surrogate
                int cp = ((b & 0x07) << 18)
                        | ((bytes[bytePos + 1] & 0x3F) << 12)
                        | ((bytes[bytePos + 2] & 0x3F) << 6)
                        | (bytes[bytePos + 3] & 0x3F);
                return Character.highSurrogate(cp);
            }
            // Advance past this character
            if (b < 0x80) {
                bytePos++;
                charIdx++;
            } else if (b < 0xE0) {
                bytePos += 2;
                charIdx++;
            } else if (b < 0xF0) {
                bytePos += 3;
                charIdx++;
            } else {
                // 4-byte → surrogate pair = 2 chars
                if (charIdx + 1 == index) {
                    // Return low surrogate
                    int cp = ((b & 0x07) << 18)
                            | ((bytes[bytePos + 1] & 0x3F) << 12)
                            | ((bytes[bytePos + 2] & 0x3F) << 6)
                            | (bytes[bytePos + 3] & 0x3F);
                    return Character.lowSurrogate(cp);
                }
                bytePos += 4;
                charIdx += 2;
            }
        }
        throw new IndexOutOfBoundsException("index=" + index + ", length=" + length());
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // Materialize for subSequence — uncommon path
        return toString().subSequence(start, end);
    }

    @Override
    public String toString() {
        String s = materialized;
        if (s != null) return s;
        s = new String(bytes, offset, byteLength, StandardCharsets.UTF_8);
        materialized = s;
        return s;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof BufferCharSequence other) {
            if (this.byteLength != other.byteLength) return false;
            for (int i = 0; i < byteLength; i++) {
                if (this.bytes[this.offset + i] != other.bytes[other.offset + i]) return false;
            }
            return true;
        }
        if (obj instanceof CharSequence cs) {
            int len = length();
            if (len != cs.length()) return false;
            for (int i = 0; i < len; i++) {
                if (charAt(i) != cs.charAt(i)) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        // Match String.hashCode() for interop when materialized
        return toString().hashCode();
    }

    /** Returns the raw UTF-8 byte length (wire length, not char count). */
    public int byteLength() {
        return byteLength;
    }

    /** Returns the backing byte array (for zero-copy re-encoding). */
    public byte[] backingArray() {
        return bytes;
    }

    /** Returns the offset into the backing array. */
    public int backingOffset() {
        return offset;
    }

    private int computeCharLength() {
        int pos = offset;
        int end = offset + byteLength;
        int count = 0;
        while (pos < end) {
            int b = bytes[pos] & 0xFF;
            if (b < 0x80) { pos++; count++; }
            else if (b < 0xE0) { pos += 2; count++; }
            else if (b < 0xF0) { pos += 3; count++; }
            else { pos += 4; count += 2; } // surrogate pair
        }
        return count;
    }
}
