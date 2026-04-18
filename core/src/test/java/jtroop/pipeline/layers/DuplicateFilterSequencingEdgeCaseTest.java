package jtroop.pipeline.layers;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for {@link DuplicateFilterLayer} and {@link SequencingLayer}.
 * Covers: short wire data, capacity-1 filter, sequence wraparound,
 * large burst of unique seqs, and combined pipeline behavior.
 */
class DuplicateFilterSequencingEdgeCaseTest {

    // ---- DuplicateFilterLayer ----

    /** Craft a wire frame with a specific 4-byte seq + payload. */
    private static ByteBuffer seqFrame(int seq) {
        var buf = ByteBuffer.allocate(8);
        buf.putInt(seq);
        buf.putInt(0); // payload byte
        buf.flip();
        return buf;
    }

    @Test
    void sequencingLayer_32bitRollover_acceptsSuccessors() {
        var layer = new SequencingLayer();
        // Seed lastReceivedSeq just below rollover by feeding an in-order packet.
        var near = seqFrame(Integer.MAX_VALUE - 1);
        assertNotNull(layer.decodeInbound(near));
        var top = seqFrame(Integer.MAX_VALUE);
        assertNotNull(layer.decodeInbound(top));

        // Rollover: next seq is Integer.MIN_VALUE (signed wrap). Pre-fix:
        // signed <= comparison says MIN_VALUE <= MAX_VALUE → dropped forever.
        // Post-fix: wrap-aware comparison accepts it.
        var rolled = seqFrame(Integer.MIN_VALUE);
        assertNotNull(layer.decodeInbound(rolled),
                "first post-rollover packet must be accepted, not dropped as stale");

        var after = seqFrame(Integer.MIN_VALUE + 1);
        assertNotNull(layer.decodeInbound(after));
    }

    @Test
    void duplicateFilter_sentinelCollision_allowsLegitimateSeqMinValue() {
        var layer = new DuplicateFilterLayer(4);
        // Pre-fix: Integer.MIN_VALUE is the EMPTY sentinel. A legitimate
        // post-rollover packet with seq=Integer.MIN_VALUE is incorrectly
        // flagged as duplicate (matches uninitialised slot) and dropped.
        var rolled = seqFrame(Integer.MIN_VALUE);
        assertNotNull(layer.decodeInbound(rolled),
                "first seq=Integer.MIN_VALUE must not collide with empty sentinel");
    }

    @Test
    void duplicateFilter_shortWire_returnsNull() {
        var layer = new DuplicateFilterLayer(8);
        // Less than 4 bytes — cannot read a seq number
        var wire = ByteBuffer.allocate(2);
        wire.put((byte) 0x01);
        wire.put((byte) 0x02);
        wire.flip();
        assertNull(layer.decodeInbound(wire));
    }

    @Test
    void duplicateFilter_emptyWire_returnsNull() {
        var layer = new DuplicateFilterLayer(8);
        var wire = ByteBuffer.allocate(0);
        assertNull(layer.decodeInbound(wire));
    }

    @Test
    void duplicateFilter_capacity1_evictsImmediately() {
        var layer = new DuplicateFilterLayer(1);
        // First packet: seq=10
        var w1 = ByteBuffer.allocate(8);
        w1.putInt(10); w1.putInt(0); w1.flip();
        assertNotNull(layer.decodeInbound(w1));

        // Duplicate: seq=10 — should be dropped
        var w2 = ByteBuffer.allocate(8);
        w2.putInt(10); w2.putInt(0); w2.flip();
        assertNull(layer.decodeInbound(w2));

        // New seq=20 evicts seq=10
        var w3 = ByteBuffer.allocate(8);
        w3.putInt(20); w3.putInt(0); w3.flip();
        assertNotNull(layer.decodeInbound(w3));

        // seq=10 is now evicted — should pass through again
        var w4 = ByteBuffer.allocate(8);
        w4.putInt(10); w4.putInt(0); w4.flip();
        assertNotNull(layer.decodeInbound(w4));
    }

    @Test
    void duplicateFilter_negativeSeqNumber_works() {
        var layer = new DuplicateFilterLayer(8);
        var w1 = ByteBuffer.allocate(8);
        w1.putInt(-1); w1.putInt(42); w1.flip();
        assertNotNull(layer.decodeInbound(w1));

        // Duplicate
        var w2 = ByteBuffer.allocate(8);
        w2.putInt(-1); w2.putInt(42); w2.flip();
        assertNull(layer.decodeInbound(w2));
    }

    @Test
    void duplicateFilter_size_tracksCorrectly() {
        var layer = new DuplicateFilterLayer(4);
        assertEquals(0, layer.size());

        for (int i = 0; i < 3; i++) {
            var w = ByteBuffer.allocate(8);
            w.putInt(i); w.putInt(0); w.flip();
            layer.decodeInbound(w);
        }
        assertEquals(3, layer.size());

        // At capacity
        var w = ByteBuffer.allocate(8);
        w.putInt(3); w.putInt(0); w.flip();
        layer.decodeInbound(w);
        assertEquals(4, layer.size());

        // Beyond capacity — size stays at capacity (ring buffer)
        var w2 = ByteBuffer.allocate(8);
        w2.putInt(4); w2.putInt(0); w2.flip();
        layer.decodeInbound(w2);
        assertEquals(4, layer.size());
    }

    @Test
    void duplicateFilter_largeBurst_allUnique() {
        int cap = 128;
        var layer = new DuplicateFilterLayer(cap);
        for (int i = 0; i < cap * 3; i++) {
            var w = ByteBuffer.allocate(8);
            w.putInt(i); w.putInt(0); w.flip();
            assertNotNull(layer.decodeInbound(w), "seq=" + i + " should pass (all unique)");
        }
    }

    // ---- SequencingLayer ----

    @Test
    void sequencing_shortWire_returnsNull() {
        var layer = new SequencingLayer();
        var wire = ByteBuffer.allocate(2);
        wire.put((byte) 1); wire.put((byte) 2); wire.flip();
        assertNull(layer.decodeInbound(wire));
    }

    @Test
    void sequencing_emptyWire_returnsNull() {
        var layer = new SequencingLayer();
        assertNull(layer.decodeInbound(ByteBuffer.allocate(0)));
    }

    @Test
    void sequencing_duplicateSeq_dropped() {
        var layer = new SequencingLayer();
        // Accept seq=0
        var w1 = ByteBuffer.allocate(8);
        w1.putInt(0); w1.putInt(42); w1.flip();
        assertNotNull(layer.decodeInbound(w1));
        assertEquals(0, layer.lastReceivedSeq());

        // Replay seq=0 — same as lastReceived, dropped (<=)
        var w2 = ByteBuffer.allocate(8);
        w2.putInt(0); w2.putInt(42); w2.flip();
        assertNull(layer.decodeInbound(w2));
    }

    @Test
    void sequencing_outOfOrder_gapAccepted_olderDropped() {
        var layer = new SequencingLayer();

        // seq=0
        var w0 = ByteBuffer.allocate(8); w0.putInt(0); w0.putInt(0); w0.flip();
        assertNotNull(layer.decodeInbound(w0));

        // seq=5 (gap over 1-4)
        var w5 = ByteBuffer.allocate(8); w5.putInt(5); w5.putInt(0); w5.flip();
        assertNotNull(layer.decodeInbound(w5));
        assertEquals(5, layer.lastReceivedSeq());

        // seq=3 (behind gap — stale)
        var w3 = ByteBuffer.allocate(8); w3.putInt(3); w3.putInt(0); w3.flip();
        assertNull(layer.decodeInbound(w3));
    }

    @Test
    void sequencing_nextSendSeq_incrementsOnEncode() {
        var layer = new SequencingLayer();
        assertEquals(0, layer.nextSendSeq());

        var payload = ByteBuffer.allocate(4); payload.putInt(1); payload.flip();
        var out = ByteBuffer.allocate(16);
        layer.encodeOutbound(payload, out);
        assertEquals(1, layer.nextSendSeq());

        payload = ByteBuffer.allocate(4); payload.putInt(2); payload.flip();
        out.clear();
        layer.encodeOutbound(payload, out);
        assertEquals(2, layer.nextSendSeq());
    }

    @Test
    void sequencing_lastReceivedSeq_initiallyMinusOne() {
        assertEquals(-1, new SequencingLayer().lastReceivedSeq());
    }

    // ---- Combined: encode → decode round-trip ----

    @Test
    void sequencing_encodeDecode_roundTrip() {
        var encoder = new SequencingLayer();
        var decoder = new SequencingLayer();

        for (int i = 0; i < 10; i++) {
            var payload = ByteBuffer.allocate(8);
            payload.putInt(i * 100);
            payload.flip();

            var wire = ByteBuffer.allocate(32);
            encoder.encodeOutbound(payload, wire);
            wire.flip();

            var decoded = decoder.decodeInbound(wire);
            assertNotNull(decoded);
            assertEquals(i * 100, decoded.getInt());
        }
    }
}
