package jtroop.pipeline;

import jtroop.ConfigurationException;
import jtroop.pipeline.layers.SequencingLayer;
import jtroop.pipeline.layers.DuplicateFilterLayer;
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

    @Test
    void duplicateFilterLayer_evictsBeyondCapacity() {
        // Capacity 4: after seeing 5 distinct seqs the oldest (0) is evicted and
        // re-receiving seq=0 should now be accepted (not a duplicate).
        var layer = new DuplicateFilterLayer(4);
        for (int seq = 0; seq < 5; seq++) {
            var buf = ByteBuffer.allocate(64);
            buf.putInt(seq);
            buf.putInt(seq * 10);
            buf.flip();
            assertNotNull(layer.decodeInbound(buf));
        }
        // Ring buffer has evicted seq=0 — it's no longer considered "seen".
        var replay = ByteBuffer.allocate(64);
        replay.putInt(0);
        replay.putInt(0);
        replay.flip();
        assertNotNull(layer.decodeInbound(replay));

        // But seq=4 (most recent) IS still in the window → duplicate.
        var dup = ByteBuffer.allocate(64);
        dup.putInt(4);
        dup.putInt(40);
        dup.flip();
        assertNull(layer.decodeInbound(dup));
    }

    @Test
    void duplicateFilterLayer_rejectsZeroCapacity() {
        assertThrows(ConfigurationException.class, () -> new DuplicateFilterLayer(0));
        assertThrows(ConfigurationException.class, () -> new DuplicateFilterLayer(-1));
    }

    @Test
    void duplicateFilterLayer_acceptsSeqMinValueFirstThenDeduplicates() {
        // Post-rollover fix (see commit 3b40baf): DuplicateFilterLayer no
        // longer uses Integer.MIN_VALUE as a sentinel — occupied slots are
        // tracked by the `size` counter, not by a special value in the
        // array. A legitimate post-rollover packet with seq=Integer.MIN_VALUE
        // is therefore accepted on first sight and deduplicated on second.
        var layer = new DuplicateFilterLayer(8);
        var buf1 = ByteBuffer.allocate(64);
        buf1.putInt(Integer.MIN_VALUE);
        buf1.putInt(1);
        buf1.flip();
        var first = layer.decodeInbound(buf1);
        assertNotNull(first, "first seq=MIN_VALUE must be accepted");

        var buf2 = ByteBuffer.allocate(64);
        buf2.putInt(Integer.MIN_VALUE);
        buf2.putInt(1);
        buf2.flip();
        var second = layer.decodeInbound(buf2);
        assertNull(second, "second seq=MIN_VALUE must be dropped as duplicate");
    }
}
