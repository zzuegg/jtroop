package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;

/**
 * Reliable UDP layer: sequence numbers, acknowledgments, retransmit on timeout.
 * Sender tracks unacked packets. Receiver generates acks.
 *
 * <p><b>Per-connection state:</b> every {@code AckLayer} instance owns a private
 * send counter, a map of unacked packets and a pending-ack slot. These are per-peer
 * concepts — a single instance shared across connections would cross-acknowledge
 * traffic between unrelated peers. Always construct a fresh instance per connection.
 *
 * <p><b>Wiring acks onto the wire:</b> this layer does NOT embed acks inside the
 * {@link #encodeOutbound} / {@link #decodeInbound} byte stream. After every decode
 * the caller must check {@link #hasAckToSend()} and, if set, call {@link #writeAck}
 * to produce an ack datagram to send back on the transport. On receipt the peer
 * feeds that datagram into {@link #processAck(ByteBuffer)}. A periodic tick must
 * also call {@link #writeRetransmits(ByteBuffer)} to resend packets that haven't
 * been acked before the retransmit timeout. See
 * {@code ReliableUdpIntegrationTest} for a concrete driver.
 *
 * <p><b>Zero-alloc on the hot path.</b> The unacked-packet store is a
 * struct-of-arrays — three parallel primitive arrays ({@code int[] seqs},
 * {@code long[] sentAtNanos}, and one flat {@code byte[] payloads} with
 * {@code int[] lengths}) and a small {@code int[] slots} ring telling us which
 * slot holds which seq. There is no {@code HashMap}, no {@code Integer}
 * boxing and no per-call {@code byte[]} allocation — an {@link #encodeOutbound}
 * call runs without any heap allocations after warmup.
 */
public final class AckLayer implements Layer {

    private final long retransmitTimeoutNanos;

    private int sendSeq = 0;
    private int lastAckedSeq = -1;   // highest seq acknowledged by peer
    private int pendingAckSeq = -1;  // seq to ack back to sender

    // Struct-of-arrays unacked ring. A slot is "occupied" iff {@code seqs[i] >= 0}.
    // Slots are recycled when processAck matches.
    private static final int INITIAL_CAPACITY = 64;
    private static final int MAX_PAYLOAD_PER_SLOT = 1500; // MTU-sized datagrams
    private int[] unackSeqs = new int[INITIAL_CAPACITY];
    private long[] unackSentAtNanos = new long[INITIAL_CAPACITY];
    private int[] unackLengths = new int[INITIAL_CAPACITY];
    // Flat payload store: slot i occupies bytes [i*MAX_PAYLOAD_PER_SLOT,
    // i*MAX_PAYLOAD_PER_SLOT + lengths[i]). One allocation at construction time.
    private byte[] unackPayloads = new byte[INITIAL_CAPACITY * MAX_PAYLOAD_PER_SLOT];
    private int unackCount = 0;

    public AckLayer() {
        this(200); // default 200ms timeout
    }

    public AckLayer(long retransmitTimeoutMs) {
        this.retransmitTimeoutNanos = retransmitTimeoutMs * 1_000_000L;
        // Initialize seqs to -1 so an "empty" slot is distinguishable.
        for (int i = 0; i < unackSeqs.length; i++) unackSeqs[i] = -1;
    }

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        int seq = sendSeq++;
        out.putInt(seq);
        int payloadStart = payload.position();
        int payloadLen = payload.remaining();
        out.put(payload);

        // Reset payload read-position so the caller can still read it (mirrors
        // the historical record-creating code path).
        payload.position(payloadStart);

        // Track for retransmit. Copy payload bytes into the flat store.
        int slot = allocateSlot();
        unackSeqs[slot] = seq;
        unackSentAtNanos[slot] = System.nanoTime();
        unackLengths[slot] = payloadLen;
        int base = slot * MAX_PAYLOAD_PER_SLOT;
        // Use bulk get into the flat store so no temporary byte[] is allocated.
        if (payloadLen > MAX_PAYLOAD_PER_SLOT) {
            throw new IllegalStateException("AckLayer payload exceeds MTU slot size: " + payloadLen);
        }
        payload.get(unackPayloads, base, payloadLen);
        // Restore read position once more — we moved it by the bulk get above.
        payload.position(payloadStart);
    }

    /** Find a free slot, growing the arrays if every slot is full. */
    private int allocateSlot() {
        var seqs = unackSeqs;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] < 0) {
                unackCount++;
                return i;
            }
        }
        // Grow — cold path (only hit if more than INITIAL_CAPACITY packets
        // are in-flight without acks).
        int newCap = seqs.length * 2;
        var newSeqs = new int[newCap];
        var newTimes = new long[newCap];
        var newLens = new int[newCap];
        var newData = new byte[newCap * MAX_PAYLOAD_PER_SLOT];
        System.arraycopy(seqs, 0, newSeqs, 0, seqs.length);
        for (int i = seqs.length; i < newCap; i++) newSeqs[i] = -1;
        System.arraycopy(unackSentAtNanos, 0, newTimes, 0, seqs.length);
        System.arraycopy(unackLengths, 0, newLens, 0, seqs.length);
        System.arraycopy(unackPayloads, 0, newData, 0, unackPayloads.length);
        unackSeqs = newSeqs;
        unackSentAtNanos = newTimes;
        unackLengths = newLens;
        unackPayloads = newData;
        unackCount++;
        return seqs.length;
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int seq = wire.getInt();
        pendingAckSeq = seq;
        // Return the same buffer, positioned past the 4-byte seq prefix.
        // {@code wire.getInt()} above advanced position by 4, so the caller
        // reads the payload from position..limit without any allocation.
        return wire;
    }

    /** Whether this layer has an ack to send back to the sender. */
    public boolean hasAckToSend() {
        return pendingAckSeq >= 0;
    }

    /** Write the pending ack into the buffer. */
    public void writeAck(ByteBuffer buf) {
        buf.putInt(pendingAckSeq);
        pendingAckSeq = -1;
    }

    /** Process an incoming ack from the receiver. */
    public void processAck(ByteBuffer ackBuf) {
        int ackedSeq = ackBuf.getInt();
        // Linear scan — no allocation, branch-predictor friendly for typical
        // in-flight window sizes (< 64).
        var seqs = unackSeqs;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] == ackedSeq) {
                seqs[i] = -1;
                unackCount--;
                break;
            }
        }
        if (ackedSeq > lastAckedSeq) {
            lastAckedSeq = ackedSeq;
        }
    }

    /** Number of packets sent but not yet acknowledged. */
    public int unackedCount() {
        return unackCount;
    }

    /** Whether any unacked packets have exceeded the retransmit timeout. */
    public boolean hasRetransmits() {
        long now = System.nanoTime();
        var seqs = unackSeqs;
        var times = unackSentAtNanos;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] >= 0 && now - times[i] >= retransmitTimeoutNanos) return true;
        }
        return false;
    }

    /**
     * Write retransmit packets (seq + payload) into the buffer.
     * Returns the number of packets retransmitted.
     */
    public int writeRetransmits(ByteBuffer buf) {
        long now = System.nanoTime();
        var seqs = unackSeqs;
        var times = unackSentAtNanos;
        var lens = unackLengths;
        var data = unackPayloads;
        int count = 0;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] >= 0 && now - times[i] >= retransmitTimeoutNanos) {
                buf.putInt(seqs[i]);
                buf.put(data, i * MAX_PAYLOAD_PER_SLOT, lens[i]);
                times[i] = now;
                count++;
            }
        }
        return count;
    }
}
