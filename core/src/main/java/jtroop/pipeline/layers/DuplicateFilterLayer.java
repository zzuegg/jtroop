package jtroop.pipeline.layers;

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

    private static final int EMPTY = Integer.MIN_VALUE;

    private final int[] seen;
    private int writeIdx;
    private int size;

    public DuplicateFilterLayer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.seen = new int[capacity];
        // Fill with sentinel — sequence numbers are non-negative in normal use,
        // so EMPTY (Integer.MIN_VALUE) never collides with a real seq.
        for (int i = 0; i < capacity; i++) seen[i] = EMPTY;
    }

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        // Pass through — duplicate filter is receive-side only
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        wire.mark();
        int seq = wire.getInt();
        wire.reset();

        if (containsSeq(seq)) {
            // Duplicate — drop the rest of the datagram.
            wire.position(wire.limit());
            return null;
        }

        rememberSeq(seq);

        // Return entire buffer (including seq — downstream layers may need it).
        var result = wire.slice(wire.position(), wire.remaining());
        wire.position(wire.limit());
        return result;
    }

    private boolean containsSeq(int seq) {
        // Linear scan — no allocation, branch-predictor friendly.
        var arr = seen;
        int n = arr.length;
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
