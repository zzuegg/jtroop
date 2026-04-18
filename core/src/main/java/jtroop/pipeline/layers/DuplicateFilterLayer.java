package jtroop.pipeline.layers;

import jtroop.ConfigurationException;
import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;

/**
 * Drops duplicate packets based on the 4-byte sequence number prefix.
 *
 * Storage is an {@code int[]} ring buffer — no boxing, zero per-packet allocation.
 * Membership check is a linear scan (O(capacity)) which is acceptable for the
 * small capacities (128-1024) typical of anti-replay windows.
 *
 * <p><b>Per-connection state:</b> this layer keeps a private window of recently
 * seen sequence numbers. On the server side, a fresh instance MUST be created
 * per connection — sharing one instance across connections would let one client's
 * sequences shadow another's. See {@link jtroop.pipeline.Layers} factory helpers.
 */
public final class DuplicateFilterLayer implements Layer {

    private final int[] seen;
    private int writeIdx;
    private int size;

    public DuplicateFilterLayer(int capacity) {
        if (capacity <= 0) throw new ConfigurationException("capacity must be positive");
        this.seen = new int[capacity];
        // No sentinel needed — {@link #containsSeq} only scans the first
        // {@code size} ring positions, which are the occupied ones. Using a
        // sentinel value (e.g. Integer.MIN_VALUE) would alias a legitimate
        // post-rollover sequence number and incorrectly flag it as duplicate.
    }

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        // Pass through — duplicate filter is receive-side only
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int startPos = wire.position();
        int seq = wire.getInt(startPos); // peek without advancing

        if (containsSeq(seq)) {
            // Duplicate — drop the rest of the datagram.
            wire.position(wire.limit());
            return null;
        }

        rememberSeq(seq);
        // Return the same buffer — downstream layers consume position
        // onwards (seq prefix still visible; AckLayer/SequencingLayer
        // re-read the 4-byte prefix). No allocation.
        return wire;
    }

    private boolean containsSeq(int seq) {
        // Linear scan over ONLY the occupied slots [0..size). Unwritten slots
        // carry the default-zero and would collide with seq=0 otherwise —
        // more importantly, any single sentinel value would collide with a
        // legitimate post-rollover sequence number.
        var arr = seen;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (arr[i] == seq) return true;
        }
        return false;
    }

    private void rememberSeq(int seq) {
        seen[writeIdx] = seq;
        writeIdx++;
        if (writeIdx == seen.length) writeIdx = 0;
        if (size < seen.length) size++;
    }

    /** Number of unique sequences currently tracked (up to capacity). */
    public int size() {
        return size;
    }
}
