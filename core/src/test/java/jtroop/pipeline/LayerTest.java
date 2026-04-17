package jtroop.pipeline;

import jtroop.ProtocolException;
import jtroop.pipeline.layers.FramingLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class LayerTest {

    @Test
    void framingLayer_outbound_prependsLength() {
        var framing = new FramingLayer();
        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.putInt(99);
        payload.flip(); // 8 bytes of payload

        var out = ByteBuffer.allocate(128);
        framing.encodeOutbound(payload, out);
        out.flip();

        // Should be: [4 bytes length = 8][8 bytes payload]
        assertEquals(8, out.getInt()); // length prefix
        assertEquals(42, out.getInt()); // payload
        assertEquals(99, out.getInt()); // payload
    }

    @Test
    void framingLayer_inbound_extractsFrame() {
        var framing = new FramingLayer();

        // Build a framed message: [length=8][payload: 42, 99]
        var wire = ByteBuffer.allocate(128);
        wire.putInt(8); // length
        wire.putInt(42);
        wire.putInt(99);
        wire.flip();

        var frame = framing.decodeInbound(wire);

        assertNotNull(frame);
        assertEquals(42, frame.getInt());
        assertEquals(99, frame.getInt());
    }

    @Test
    void framingLayer_inbound_returnsNullOnIncompleteLength() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(2);
        wire.put((byte) 0);
        wire.put((byte) 1);
        wire.flip();

        var frame = framing.decodeInbound(wire);
        assertNull(frame); // not enough bytes for length prefix
    }

    @Test
    void framingLayer_inbound_returnsNullOnIncompletePayload() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(8);
        wire.putInt(100); // claims 100 bytes payload
        wire.putInt(42);  // but only 4 bytes available
        wire.flip();

        var frame = framing.decodeInbound(wire);
        assertNull(frame); // not enough payload bytes
    }

    @Test
    void framingLayer_roundtrip() {
        var framing = new FramingLayer();

        // Encode
        var payload = ByteBuffer.allocate(64);
        payload.putFloat(3.14f);
        payload.putLong(12345L);
        payload.flip();
        var expectedPayloadSize = payload.remaining();

        var wire = ByteBuffer.allocate(128);
        framing.encodeOutbound(payload, wire);
        wire.flip();

        // Decode
        var frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(expectedPayloadSize, frame.remaining());
        assertEquals(3.14f, frame.getFloat());
        assertEquals(12345L, frame.getLong());
    }

    @Test
    void framingLayer_multipleFrames() {
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(256);

        // Write two frames
        var p1 = ByteBuffer.allocate(16);
        p1.putInt(1);
        p1.flip();
        framing.encodeOutbound(p1, wire);

        var p2 = ByteBuffer.allocate(16);
        p2.putInt(2);
        p2.flip();
        framing.encodeOutbound(p2, wire);

        wire.flip();

        // Read both frames back
        var f1 = framing.decodeInbound(wire);
        assertNotNull(f1);
        assertEquals(1, f1.getInt());

        var f2 = framing.decodeInbound(wire);
        assertNotNull(f2);
        assertEquals(2, f2.getInt());
    }

    @Test
    void pipeline_composesMultipleLayers() {
        var pipeline = new Pipeline(new FramingLayer());

        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.flip();

        var wire = ByteBuffer.allocate(128);
        pipeline.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = pipeline.decodeInbound(wire);
        assertNotNull(decoded);
        assertEquals(42, decoded.getInt());
    }

    @Test
    void framingLayer_viewReused_sameBuffer() {
        // Verifies the cached view is reused across calls against the same
        // backing buffer — critical for the zero-alloc property.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(256);
        wire.putInt(4); wire.putInt(11);
        wire.putInt(4); wire.putInt(22);
        wire.flip();

        var f1 = framing.decodeInbound(wire);
        var f2 = framing.decodeInbound(wire);
        assertSame(f1, f2, "cached view must be reused across frames on same wire");
        // Second call overwrote f1's pos/limit — by contract the caller drains
        // f1 before calling decodeInbound again, so assert on fresh state of f2.
        assertEquals(22, f2.getInt());
    }

    @Test
    void framingLayer_viewDoesNotMutateWireLimit() {
        // Regression: previous attempts that manipulated wire.limit() to build
        // a view broke compact()/clear() semantics and caused
        // "IllegalArgumentException: newPosition > limit" later.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(64);
        wire.putInt(4); wire.putInt(77);
        wire.putInt(4); wire.putInt(88);
        wire.flip();
        int originalLimit = wire.limit();

        framing.decodeInbound(wire);
        assertEquals(originalLimit, wire.limit(), "wire limit must not be mutated");

        framing.decodeInbound(wire);
        assertEquals(originalLimit, wire.limit(), "wire limit must not be mutated");

        // compact() must work cleanly after decoding — simulates the Server/Client
        // hot path that calls readBuf.compact() after draining frames.
        wire.compact();
        assertEquals(0, wire.position());
        assertEquals(wire.capacity(), wire.limit());
    }

    @Test
    void framingLayer_partialFrame_thenCompleted() {
        // Simulates a frame arriving across two socket reads.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(64);
        // Write [len=8][partial: 4 bytes only]
        wire.putInt(8);
        wire.putInt(42);
        wire.flip();

        // First attempt: not enough bytes → null, no state change
        var frame = framing.decodeInbound(wire);
        assertNull(frame);
        assertEquals(0, wire.position(), "position must be unchanged on incomplete frame");

        // Second read appends the missing 4 bytes
        wire.position(wire.limit());
        wire.limit(wire.capacity());
        wire.putInt(99);
        wire.flip();

        frame = framing.decodeInbound(wire);
        assertNotNull(frame);
        assertEquals(8, frame.remaining());
        assertEquals(42, frame.getInt());
        assertEquals(99, frame.getInt());
    }

    @Test
    void framingLayer_threeFramesInOneBuffer() {
        // Multiple frames batched into a single socket read — the Forwarder
        // and Server hot paths rely on this working without allocations.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(256);
        wire.putInt(4); wire.putInt(100);
        wire.putInt(4); wire.putInt(200);
        wire.putInt(4); wire.putInt(300);
        wire.flip();

        var f1 = framing.decodeInbound(wire);
        assertNotNull(f1);
        assertEquals(100, f1.getInt());

        var f2 = framing.decodeInbound(wire);
        assertNotNull(f2);
        assertEquals(200, f2.getInt());

        var f3 = framing.decodeInbound(wire);
        assertNotNull(f3);
        assertEquals(300, f3.getInt());

        // No more frames
        assertNull(framing.decodeInbound(wire));
        assertFalse(wire.hasRemaining());
    }

    @Test
    void framingLayer_frameSpanningReads_viaCompact() {
        // Exact reproduction of Server.handleRead's pattern:
        //   readBuf.flip(); decode until null; readBuf.compact();
        // A frame that straddles two reads must decode correctly after compact.
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(32);

        // Read 1: complete frame + start of next
        wire.putInt(4); wire.putInt(11);  // frame 1 complete (8 bytes)
        wire.putInt(8); wire.putInt(22);  // frame 2: len=8, only 4 of 8 bytes
        wire.flip();

        var f = framing.decodeInbound(wire);
        assertNotNull(f);
        assertEquals(11, f.getInt());

        // Next frame is incomplete — returns null, leaves position at frame start
        assertNull(framing.decodeInbound(wire));
        wire.compact();  // keep partial frame, reset for more data

        // Read 2: remainder arrives
        wire.putInt(33);
        wire.flip();

        f = framing.decodeInbound(wire);
        assertNotNull(f);
        assertEquals(22, f.getInt());
        assertEquals(33, f.getInt());
    }

    @Test
    void framingLayer_viewRebindsForDifferentBuffers() {
        // When the layer is shared across connections (same Pipeline, different
        // readBufs), the view must rebind rather than point into a stale array.
        var framing = new FramingLayer();

        var wireA = ByteBuffer.allocate(64);
        wireA.putInt(4); wireA.putInt(0xAAAA);
        wireA.flip();
        var fA = framing.decodeInbound(wireA);
        assertEquals(0xAAAA, fA.getInt());

        var wireB = ByteBuffer.allocate(64);
        wireB.putInt(4); wireB.putInt(0xBBBB);
        wireB.flip();
        var fB = framing.decodeInbound(wireB);
        assertEquals(0xBBBB, fB.getInt(), "view must rebind when wire buffer changes");
    }

    @Test
    void framingLayer_negativeLengthRejected() {
        // Defensive: corrupt length prefix throws FramingException (caught by
        // Server.handleRead which closes the misbehaving connection).
        var framing = new FramingLayer();
        var wire = ByteBuffer.allocate(16);
        wire.putInt(-1);
        wire.putInt(0);
        wire.flip();

        assertThrows(ProtocolException.class, () -> framing.decodeInbound(wire));
    }
}
