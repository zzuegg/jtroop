package jtroop.pipeline;

import jtroop.pipeline.layers.FramingLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary conditions for the length-prefixed framing layer. Exercises the
 * paths that a partial-read / fragmented TCP stream and a malicious peer
 * can produce.
 */
class FramingLayerBoundaryTest {

    @Test
    void rejectsNegativeLength() {
        // A garbage or hostile peer sends a negative length prefix. Previously
        // this triggered BufferUnderflowException or an IndexOutOfBounds from
        // slice(). Now it must be rejected cleanly so the server can close the
        // connection instead of dying on an internal buffer exception.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(16);
        wire.putInt(-1);
        wire.flip();
        assertThrows(IllegalStateException.class, () -> framing.decodeInbound(wire));
    }

    @Test
    void rejectsLengthAboveMax() {
        // length > MAX_FRAME_LENGTH can never be satisfied by the read loop:
        // the buffer isn't big enough, so we'd return null forever and stall.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(16);
        wire.putInt(FramingLayer.MAX_FRAME_LENGTH + 1);
        wire.flip();
        assertThrows(IllegalStateException.class, () -> framing.decodeInbound(wire));
    }

    @Test
    void rejectsHugeLength() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(16);
        wire.putInt(Integer.MAX_VALUE);
        wire.flip();
        assertThrows(IllegalStateException.class, () -> framing.decodeInbound(wire));
    }

    @Test
    void zeroLengthFrame_isValid() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(8);
        wire.putInt(0);
        wire.flip();
        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(0, frame.remaining());
    }

    @Test
    void partialFrame_preservesStateForRetry() {
        // Simulate a fragmented TCP read: length prefix says 8 bytes but only
        // 4 bytes of payload are available. decodeInbound must return null AND
        // leave the wire position untouched so the caller can append more
        // bytes and retry.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(32);
        wire.putInt(8);
        wire.putInt(42); // only 4 bytes of the 8-byte payload
        wire.flip();
        int posBefore = wire.position();
        assertNull(framing.decodeInbound(wire));
        assertEquals(posBefore, wire.position(),
                "position must be untouched so the caller can retry after append");
    }

    @Test
    void partialFrame_completesAfterMoreBytes() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(32);
        wire.putInt(8);
        wire.putInt(42); // only first 4 of 8 payload bytes
        wire.flip();
        assertNull(framing.decodeInbound(wire));

        // Simulate: caller compact()s and more bytes arrive. Here we just
        // extend the limit to expose the trailing bytes we already wrote.
        wire.clear();
        wire.putInt(8);
        wire.putInt(42);
        wire.putInt(99);
        wire.flip();

        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(8, frame.remaining());
        assertEquals(42, frame.getInt());
        assertEquals(99, frame.getInt());
    }

    @Test
    void maxLengthFrame_roundtrip() {
        // Max-size payload (MAX_FRAME_LENGTH bytes) must encode + decode.
        var framing = new FramingLayer();
        int max = FramingLayer.MAX_FRAME_LENGTH;
        var payload = ByteBuffer.allocate(max);
        for (int i = 0; i < max; i++) payload.put((byte) (i & 0xFF));
        payload.flip();

        var wire = ByteBuffer.allocate(max + 4);
        framing.encodeOutbound(payload, wire);
        wire.flip();
        assertEquals(max + 4, wire.remaining());

        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(max, frame.remaining());
        for (int i = 0; i < max; i++) {
            assertEquals((byte) (i & 0xFF), frame.get());
        }
    }

    @Test
    void lengthPrefixSpanningReads_isHandled() {
        // TCP can split a frame anywhere — even inside the length prefix.
        // First read delivers only 2 of the 4 length bytes. decodeInbound must
        // return null AND leave position at 0 so the caller can accumulate.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(32);
        wire.put((byte) 0);
        wire.put((byte) 0);
        wire.flip(); // 2 bytes available, need 4 for length
        assertNull(framing.decodeInbound(wire));
        assertEquals(0, wire.position());

        // Now accumulate: 2 more length bytes + 4 payload bytes arrive.
        wire.position(wire.limit()); // unread the 2 bytes
        wire.limit(wire.capacity());
        wire.put((byte) 0);
        wire.put((byte) 4);
        wire.putInt(0xCAFEBABE);
        wire.flip();

        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(0xCAFEBABE, frame.getInt());
    }

    @Test
    void multipleFramesBackToBack() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(256);
        for (int i = 0; i < 5; i++) {
            wire.putInt(4);
            wire.putInt(i * 100);
        }
        wire.flip();

        for (int i = 0; i < 5; i++) {
            var f = framing.decodeInbound(wire);
            assertNotNull(f, "frame " + i + " should decode");
            assertEquals(i * 100, f.getInt());
        }
        assertNull(framing.decodeInbound(wire)); // no more data
    }

    @Test
    void partialSecondFrame_firstStillExtracted() {
        // Two frames, but only half of the second is available. First must
        // decode cleanly; second must return null. This mirrors what happens
        // when the kernel delivers N bytes spanning one complete + one partial
        // frame.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(64);
        wire.putInt(4); wire.putInt(1);
        wire.putInt(8); wire.putInt(2); // claims 8 bytes, only gave 4
        wire.flip();

        var f1 = framing.decodeInbound(wire);
        assertNotNull(f1);
        assertEquals(1, f1.getInt());

        int posBefore = wire.position();
        assertNull(framing.decodeInbound(wire));
        assertEquals(posBefore, wire.position(),
                "after partial, position must be untouched so compact() preserves data");
    }
}
