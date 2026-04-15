package net.pipeline;

import net.pipeline.layers.SequencingLayer;
import net.pipeline.layers.DuplicateFilterLayer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class UdpLayerTest {

    @Test
    void sequencingLayer_addsSequenceNumber() {
        var layer = new SequencingLayer();
        var payload = ByteBuffer.allocate(64);
        payload.putInt(42);
        payload.flip();

        var out = ByteBuffer.allocate(128);
        layer.encodeOutbound(payload, out);
        out.flip();

        // First 4 bytes should be sequence number
        int seq = out.getInt();
        assertEquals(0, seq); // first message = seq 0
        assertEquals(42, out.getInt()); // payload follows
    }

    @Test
    void sequencingLayer_incrementsSequence() {
        var layer = new SequencingLayer();

        for (int i = 0; i < 5; i++) {
            var payload = ByteBuffer.allocate(16);
            payload.putInt(i);
            payload.flip();
            var out = ByteBuffer.allocate(64);
            layer.encodeOutbound(payload, out);
            out.flip();
            assertEquals(i, out.getInt()); // sequence number
        }
    }

    @Test
    void sequencingLayer_decodesAndReturnsPayload() {
        var encode = new SequencingLayer();
        var decode = new SequencingLayer();

        var payload = ByteBuffer.allocate(16);
        payload.putInt(42);
        payload.flip();

        var wire = ByteBuffer.allocate(64);
        encode.encodeOutbound(payload, wire);
        wire.flip();

        var decoded = decode.decodeInbound(wire);
        assertNotNull(decoded);
        assertEquals(42, decoded.getInt());
    }

    @Test
    void sequencingLayer_dropsStalePackets() {
        var encode = new SequencingLayer();
        var decode = new SequencingLayer();

        // Send packets 0, 1, 2
        var packets = new ByteBuffer[3];
        for (int i = 0; i < 3; i++) {
            var p = ByteBuffer.allocate(16);
            p.putInt(i);
            p.flip();
            packets[i] = ByteBuffer.allocate(64);
            encode.encodeOutbound(p, packets[i]);
            packets[i].flip();
        }

        // Deliver in order: 0, 2 (skip 1), then 1 (stale)
        assertNotNull(decode.decodeInbound(packets[0])); // seq 0 → accept
        assertNotNull(decode.decodeInbound(packets[2])); // seq 2 → accept (newer)
        assertNull(decode.decodeInbound(packets[1]));    // seq 1 → stale, drop
    }

    @Test
    void duplicateFilterLayer_firstPassThrough() {
        var layer = new DuplicateFilterLayer(100);

        var wire = ByteBuffer.allocate(64);
        wire.putInt(0); // fake seq number
        wire.putInt(42); // payload
        wire.flip();

        var result = layer.decodeInbound(wire);
        assertNotNull(result);
    }

    @Test
    void duplicateFilterLayer_duplicateDropped() {
        var layer = new DuplicateFilterLayer(100);

        // First time
        var wire1 = ByteBuffer.allocate(64);
        wire1.putInt(0);
        wire1.putInt(42);
        wire1.flip();
        assertNotNull(layer.decodeInbound(wire1));

        // Same seq number again
        var wire2 = ByteBuffer.allocate(64);
        wire2.putInt(0);
        wire2.putInt(42);
        wire2.flip();
        assertNull(layer.decodeInbound(wire2));
    }

    @Test
    void duplicateFilterLayer_differentSeqPassThrough() {
        var layer = new DuplicateFilterLayer(100);

        var wire1 = ByteBuffer.allocate(64);
        wire1.putInt(0);
        wire1.putInt(1);
        wire1.flip();

        var wire2 = ByteBuffer.allocate(64);
        wire2.putInt(1);
        wire2.putInt(2);
        wire2.flip();

        assertNotNull(layer.decodeInbound(wire1));
        assertNotNull(layer.decodeInbound(wire2));
    }

    @Test
    void duplicateFilterLayer_encodePassesThrough() {
        var layer = new DuplicateFilterLayer(100);
        var payload = ByteBuffer.allocate(16);
        payload.putInt(42);
        payload.flip();

        var out = ByteBuffer.allocate(64);
        layer.encodeOutbound(payload, out);
        out.flip();
        assertEquals(42, out.getInt()); // passes through unchanged
    }
}
