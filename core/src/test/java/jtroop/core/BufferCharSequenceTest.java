package jtroop.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BufferCharSequence} — the zero-allocation CharSequence
 * backed by a UTF-8 byte region. Covers ASCII, multi-byte, surrogate pairs,
 * empty input, offset handling, and equality semantics.
 */
class BufferCharSequenceTest {

    private static BufferCharSequence of(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new BufferCharSequence(bytes, 0, bytes.length);
    }

    private static BufferCharSequence ofOffset(String s, int padBefore) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[padBefore + utf8.length + 4]; // extra tail padding
        System.arraycopy(utf8, 0, buf, padBefore, utf8.length);
        return new BufferCharSequence(buf, padBefore, utf8.length);
    }

    // --- empty ---

    @Test
    void empty_lengthIsZero() {
        var cs = of("");
        assertEquals(0, cs.length());
    }

    @Test
    void empty_toStringIsEmpty() {
        assertEquals("", of("").toString());
    }

    @Test
    void empty_charAtThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> of("").charAt(0));
    }

    // --- ASCII ---

    @Test
    void ascii_lengthMatchesString() {
        var cs = of("hello");
        assertEquals(5, cs.length());
    }

    @Test
    void ascii_charAtReturnsCorrectChars() {
        var cs = of("abc");
        assertEquals('a', cs.charAt(0));
        assertEquals('b', cs.charAt(1));
        assertEquals('c', cs.charAt(2));
    }

    @Test
    void ascii_toStringRoundTrips() {
        assertEquals("hello world", of("hello world").toString());
    }

    @Test
    void ascii_charAtOutOfBoundsThrows() {
        var cs = of("ab");
        assertThrows(IndexOutOfBoundsException.class, () -> cs.charAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> cs.charAt(-1));
    }

    // --- multi-byte UTF-8 (2-byte and 3-byte sequences) ---

    @Test
    void twoByte_utf8_lengthAndCharAt() {
        // U+00E9 = e-acute, 2-byte UTF-8: C3 A9
        var cs = of("\u00E9");
        assertEquals(1, cs.length());
        assertEquals('\u00E9', cs.charAt(0));
    }

    @Test
    void threeByte_utf8_lengthAndCharAt() {
        // U+20AC = Euro sign, 3-byte UTF-8: E2 82 AC
        var cs = of("\u20AC");
        assertEquals(1, cs.length());
        assertEquals('\u20AC', cs.charAt(0));
    }

    @Test
    void mixed_asciiAndMultiByte() {
        // "a\u00E9b\u20AC" = 4 chars, but 1+2+1+3 = 7 bytes
        var cs = of("a\u00E9b\u20AC");
        assertEquals(4, cs.length());
        assertEquals('a', cs.charAt(0));
        assertEquals('\u00E9', cs.charAt(1));
        assertEquals('b', cs.charAt(2));
        assertEquals('\u20AC', cs.charAt(3));
        assertEquals(7, cs.byteLength());
    }

    // --- 4-byte UTF-8 (surrogate pairs) ---

    @Test
    void fourByte_surrogatesPair_lengthIsTwoChars() {
        // U+1F600 (grinning face) = 4-byte UTF-8, produces a surrogate pair
        String emoji = "\uD83D\uDE00";
        var cs = of(emoji);
        assertEquals(2, cs.length()); // surrogate pair = 2 chars
        assertEquals(Character.highSurrogate(0x1F600), cs.charAt(0));
        assertEquals(Character.lowSurrogate(0x1F600), cs.charAt(1));
    }

    @Test
    void fourByte_toString_matchesOriginal() {
        String emoji = "\uD83D\uDE00";
        assertEquals(emoji, of(emoji).toString());
    }

    @Test
    void fourByte_mixedWithAscii() {
        // "A\uD83D\uDE00B" = 3 code points but 4 chars (surrogate pair)
        String s = "A\uD83D\uDE00B";
        var cs = of(s);
        assertEquals(4, cs.length()); // A + high + low + B
        assertEquals('A', cs.charAt(0));
        assertEquals(Character.highSurrogate(0x1F600), cs.charAt(1));
        assertEquals(Character.lowSurrogate(0x1F600), cs.charAt(2));
        assertEquals('B', cs.charAt(3));
    }

    // --- offset handling ---

    @Test
    void nonZeroOffset_readsCorrectly() {
        var cs = ofOffset("hello", 10);
        assertEquals(5, cs.length());
        assertEquals('h', cs.charAt(0));
        assertEquals("hello", cs.toString());
    }

    // --- equals ---

    @Test
    void equals_sameContent_twoBufferCharSequences() {
        var a = of("test");
        var b = of("test");
        assertEquals(a, b);
    }

    @Test
    void equals_differentContent() {
        assertNotEquals(of("abc"), of("def"));
    }

    @Test
    void equals_differentLength() {
        assertNotEquals(of("ab"), of("abc"));
    }

    @Test
    void equals_withString() {
        assertTrue(of("hello").equals("hello"));
    }

    @Test
    void equals_withString_differentContent() {
        assertFalse(of("hello").equals("world"));
    }

    @Test
    void equals_reflexive() {
        var cs = of("x");
        assertEquals(cs, cs);
    }

    @Test
    void equals_nullReturnsFalse() {
        assertFalse(of("x").equals(null));
    }

    // --- hashCode ---

    @Test
    void hashCode_matchesStringHashCode() {
        String s = "hello";
        assertEquals(s.hashCode(), of(s).hashCode());
    }

    // --- subSequence ---

    @Test
    void subSequence_asciiRange() {
        var cs = of("abcdef");
        assertEquals("bcd", cs.subSequence(1, 4).toString());
    }

    @Test
    void subSequence_zeroAllocView_returnsBufferCharSequence_onCleanBoundaries() {
        // Zero-alloc path: both endpoints on clean char boundaries → a
        // BufferCharSequence view over the same backing array.
        var cs = of("hello world");
        var sub = cs.subSequence(6, 11);
        assertEquals("world", sub.toString());
        assertInstanceOf(BufferCharSequence.class, sub,
                "clean-boundary subSequence must return a BufferCharSequence view, not a String");
    }

    @Test
    void subSequence_multibyteUtf8_boundaries() {
        // 'é' (U+00E9, 2-byte), '中' (U+4E2D, 3-byte), '😀' (U+1F600, 4-byte / surrogate pair)
        var cs = of("a\u00e9\u4e2d\uD83D\uDE00"); // length = 5 chars (surrogate pair counts as 2)
        assertEquals("a", cs.subSequence(0, 1).toString());
        assertEquals("\u00e9", cs.subSequence(1, 2).toString());
        assertEquals("\u4e2d", cs.subSequence(2, 3).toString());
        // End-of-string including full surrogate pair.
        assertEquals("\uD83D\uDE00", cs.subSequence(3, 5).toString());
    }

    @Test
    void subSequence_midSurrogatePair_fallsBackToString() {
        // A surrogate pair occupies char indices 3 and 4. Ending at index 4
        // would split the pair — must fall back to the materialised String.
        var cs = of("a\u00e9\u4e2d\uD83D\uDE00");
        var sub = cs.subSequence(0, 4);
        // Start is on a clean boundary, end is mid-pair → fallback.
        // Result must be a 4-char string containing the high surrogate.
        assertEquals(4, sub.length());
        assertEquals('\uD83D', sub.charAt(3));
    }

    @Test
    void subSequence_emptyRange() {
        var cs = of("abcdef");
        assertEquals("", cs.subSequence(2, 2).toString());
    }

    @Test
    void subSequence_fullRange_returnsEquivalent() {
        var cs = of("abcdef");
        assertEquals("abcdef", cs.subSequence(0, 6).toString());
    }

    @Test
    void subSequence_outOfBounds_throws() {
        var cs = of("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> cs.subSequence(-1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> cs.subSequence(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> cs.subSequence(2, 1));
    }

    // --- toString caching ---

    @Test
    void toString_isCached() {
        var cs = of("cached");
        String first = cs.toString();
        String second = cs.toString();
        assertSame(first, second);
    }

    // --- backing array accessors ---

    @Test
    void backingArray_andOffset_provideRawAccess() {
        byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
        var cs = new BufferCharSequence(raw, 0, raw.length);
        assertSame(raw, cs.backingArray());
        assertEquals(0, cs.backingOffset());
        assertEquals(3, cs.byteLength());
    }
}
