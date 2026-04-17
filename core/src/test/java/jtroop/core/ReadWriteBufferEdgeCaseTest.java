package jtroop.core;

import org.junit.jupiter.api.Test;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for {@link ReadBuffer} and {@link WriteBuffer}.
 * Covers: empty strings, multibyte UTF-8, surrogate pairs, CharSequence
 * round-trips, buffer underflow, and direct vs heap-backed buffer paths.
 */
class ReadWriteBufferEdgeCaseTest {

    // --- Empty string ---

    @Test
    void emptyString_roundTrips() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeString("");
        buf.flip();
        assertEquals("", new ReadBuffer(buf).readString());
    }

    @Test
    void emptyString_consumesOnlyTwoBytes() {
        var buf = ByteBuffer.allocate(64);
        var wb = new WriteBuffer(buf);
        wb.writeString("");
        assertEquals(2, wb.position()); // just the length prefix
    }

    // --- Multi-byte UTF-8 strings ---

    @Test
    void twoByte_utf8_string_roundTrips() {
        String s = "caf\u00E9"; // e-acute = 2-byte UTF-8
        var buf = ByteBuffer.allocate(64);
        new WriteBuffer(buf).writeString(s);
        buf.flip();
        assertEquals(s, new ReadBuffer(buf).readString());
    }

    @Test
    void threeByte_utf8_string_roundTrips() {
        String s = "\u20AC100"; // Euro sign = 3-byte UTF-8
        var buf = ByteBuffer.allocate(64);
        new WriteBuffer(buf).writeString(s);
        buf.flip();
        assertEquals(s, new ReadBuffer(buf).readString());
    }

    @Test
    void fourByte_surrogates_string_roundTrips() {
        String s = "Hi\uD83D\uDE00!"; // emoji = 4-byte UTF-8
        var buf = ByteBuffer.allocate(64);
        new WriteBuffer(buf).writeString(s);
        buf.flip();
        assertEquals(s, new ReadBuffer(buf).readString());
    }

    // --- CharSequence write/read round-trip ---

    @Test
    void charSequence_roundTrip_viaBufferCharSequence() {
        var buf1 = ByteBuffer.allocate(128);
        new WriteBuffer(buf1).writeString("hello");
        buf1.flip();
        CharSequence cs = new ReadBuffer(buf1).readCharSequence();

        // Now re-encode the CharSequence (which is a BufferCharSequence)
        var buf2 = ByteBuffer.allocate(128);
        new WriteBuffer(buf2).writeCharSequence(cs);
        buf2.flip();
        assertEquals("hello", new ReadBuffer(buf2).readString());
    }

    @Test
    void readCharSequence_emptyString_returnsEmptyCharSequence() {
        var buf = ByteBuffer.allocate(64);
        new WriteBuffer(buf).writeString("");
        buf.flip();
        CharSequence cs = new ReadBuffer(buf).readCharSequence();
        assertEquals(0, cs.length());
        assertEquals("", cs.toString());
    }

    @Test
    void readCharSequence_multibyte_returnsBufferCharSequence() {
        String s = "caf\u00E9";
        var buf = ByteBuffer.allocate(64);
        new WriteBuffer(buf).writeString(s);
        buf.flip();
        CharSequence cs = new ReadBuffer(buf).readCharSequence();
        assertInstanceOf(BufferCharSequence.class, cs);
        assertEquals(s, cs.toString());
    }

    // --- Direct ByteBuffer path ---

    @Test
    void directBuffer_stringRoundTrips() {
        String s = "direct-test-\u00E9";
        var buf = ByteBuffer.allocateDirect(128);
        WriteBuffer.writeUtf8(buf, s);
        buf.flip();
        assertEquals(s, ReadBuffer.readUtf8(buf));
    }

    @Test
    void directBuffer_charSequenceRoundTrips() {
        String s = "direct-cs-\u20AC";
        var buf = ByteBuffer.allocateDirect(128);
        WriteBuffer.writeUtf8(buf, s);
        buf.flip();
        CharSequence cs = ReadBuffer.readUtf8CharSequence(buf);
        assertEquals(s, cs.toString());
    }

    // --- Buffer underflow ---

    @Test
    void readInt_onEmptyBuffer_throws() {
        var buf = ByteBuffer.allocate(0);
        var rb = new ReadBuffer(buf);
        assertThrows(BufferUnderflowException.class, rb::readInt);
    }

    @Test
    void readString_onEmptyBuffer_throws() {
        var buf = ByteBuffer.allocate(0);
        var rb = new ReadBuffer(buf);
        assertThrows(BufferUnderflowException.class, rb::readString);
    }

    @Test
    void readBytes_moreThanAvailable_throws() {
        var buf = ByteBuffer.allocate(4);
        buf.putInt(42);
        buf.flip();
        var rb = new ReadBuffer(buf);
        assertThrows(BufferUnderflowException.class, () -> rb.readBytes(8));
    }

    // --- Generic CharSequence path (not String, not BufferCharSequence) ---

    @Test
    void writeCharSequence_genericCharSequence_roundTrips() {
        // StringBuilder is neither String nor BufferCharSequence
        CharSequence sb = new StringBuilder("generic");
        var buf = ByteBuffer.allocate(64);
        WriteBuffer.writeUtf8CharSequence(buf, sb);
        buf.flip();
        assertEquals("generic", ReadBuffer.readUtf8(buf));
    }

    @Test
    void writeCharSequence_genericWithMultibyte_roundTrips() {
        CharSequence sb = new StringBuilder("\u00E9\u20AC\uD83D\uDE00");
        var buf = ByteBuffer.allocate(64);
        WriteBuffer.writeUtf8CharSequence(buf, sb);
        buf.flip();
        assertEquals("\u00E9\u20AC\uD83D\uDE00", ReadBuffer.readUtf8(buf));
    }

    // --- Multiple sequential reads ---

    @Test
    void multipleStrings_sequentialReadWrite() {
        var buf = ByteBuffer.allocate(256);
        var wb = new WriteBuffer(buf);
        wb.writeString("first");
        wb.writeString("");
        wb.writeString("\u20AC");
        buf.flip();
        var rb = new ReadBuffer(buf);
        assertEquals("first", rb.readString());
        assertEquals("", rb.readString());
        assertEquals("\u20AC", rb.readString());
        assertEquals(0, rb.remaining());
    }
}
