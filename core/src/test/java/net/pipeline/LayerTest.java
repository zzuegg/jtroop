package net.pipeline;

import net.pipeline.layers.FramingLayer;
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
}
