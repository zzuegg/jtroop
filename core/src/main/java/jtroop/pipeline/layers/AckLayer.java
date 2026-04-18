package jtroop.pipeline.layers;

import jtroop.ProtocolException;
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

    /** Default number of retransmit attempts before a packet is dropped. */
    public static final int DEFAULT_MAX_RETRIES = 10;

    private final long baseTimeoutNanos;
    private final int maxRetries;

    private int sendSeq = 0;
    private int lastAckedSeq = -1;   // highest seq acknowledged by peer
    private int pendingAckSeq = -1;  // seq to ack back to sender

    // Struct-of-arrays unacked ring. A slot is "occupied" iff {@code seqs[i] >= 0}.
    // Slots are recycled when processAck matches.
    private static final int INITIAL_CAPACITY = 64;
    private static final int MAX_PAYLOAD_PER_SLOT = 1500; // MTU-sized datagrams
    /** Cap the backoff doubling so retries don't stretch beyond ~64×base. */
    private static final int MAX_BACKOFF_SHIFT = 6;

    private int[] unackSeqs = new int[INITIAL_CAPACITY];
    /** When the slot is next eligible for retransmit (nanos, absolute). */
    private long[] unackNextRetryAt = new long[INITIAL_CAPACITY];
    /** How many retransmits this slot has already emitted. */
    private byte[] unackRetries = new byte[INITIAL_CAPACITY];
    private int[] unackLengths = new int[INITIAL_CAPACITY];
    // Flat payload store: slot i occupies bytes [i*MAX_PAYLOAD_PER_SLOT,
    // i*MAX_PAYLOAD_PER_SLOT + lengths[i]). One allocation at construction time.
    private byte[] unackPayloads = new byte[INITIAL_CAPACITY * MAX_PAYLOAD_PER_SLOT];
    private int unackCount = 0;
    /** Cumulative count of packets dropped because their retry budget was
     *  exhausted. Callers use this to decide whether to tear down the
     *  connection. Never resets. */
    private int exhaustedCount = 0;

    public AckLayer() {
        this(200L, DEFAULT_MAX_RETRIES);
    }

    public AckLayer(long retransmitTimeoutMs) {
        this(retransmitTimeoutMs, DEFAULT_MAX_RETRIES);
    }

    public AckLayer(long retransmitTimeoutMs, int maxRetries) {
        if (retransmitTimeoutMs <= 0) {
            throw new jtroop.ConfigurationException("retransmitTimeoutMs must be positive");
        }
        if (maxRetries < 1) {
            throw new jtroop.ConfigurationException("maxRetries must be >= 1");
        }
        this.baseTimeoutNanos = retransmitTimeoutMs * 1_000_000L;
        this.maxRetries = maxRetries;
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
        unackNextRetryAt[slot] = System.nanoTime() + baseTimeoutNanos;
        unackRetries[slot] = 0;
        unackLengths[slot] = payloadLen;
        int base = slot * MAX_PAYLOAD_PER_SLOT;
        // Use bulk get into the flat store so no temporary byte[] is allocated.
        if (payloadLen > MAX_PAYLOAD_PER_SLOT) {
            throw new ProtocolException("AckLayer payload exceeds MTU slot size: " + payloadLen);
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
        var newRetries = new byte[newCap];
        var newLens = new int[newCap];
        var newData = new byte[newCap * MAX_PAYLOAD_PER_SLOT];
        System.arraycopy(seqs, 0, newSeqs, 0, seqs.length);
        for (int i = seqs.length; i < newCap; i++) newSeqs[i] = -1;
        System.arraycopy(unackNextRetryAt, 0, newTimes, 0, seqs.length);
        System.arraycopy(unackRetries, 0, newRetries, 0, seqs.length);
        System.arraycopy(unackLengths, 0, newLens, 0, seqs.length);
        System.arraycopy(unackPayloads, 0, newData, 0, unackPayloads.length);
        unackSeqs = newSeqs;
        unackNextRetryAt = newTimes;
        unackRetries = newRetries;
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

    /** Whether any unacked packets are due for retransmit. */
    public boolean hasRetransmits() {
        long now = System.nanoTime();
        var seqs = unackSeqs;
        var nextAt = unackNextRetryAt;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] >= 0 && now >= nextAt[i]) return true;
        }
        return false;
    }

    /**
     * Write retransmit packets (seq + payload) into the buffer. Packets that
     * have hit the retry cap are dropped — their slot is released and
     * {@link #exhaustedCount()} advances — instead of being retransmitted
     * yet again, which would otherwise be an amplification vector against
     * silent peers.
     *
     * <p>Exponential backoff: each successive retry for the same slot is
     * scheduled {@code baseTimeoutNanos << min(retries, 6)} nanos in the
     * future, so a truly-silent peer generates retries at 1×, 2×, 4×, 8×,
     * 16×, 32×, 64×, 64×, ... the base timeout. Bounded shift — no
     * {@code Math.pow}, no allocation.
     *
     * @return the number of packets retransmitted (not dropped)
     */
    public int writeRetransmits(ByteBuffer buf) {
        long now = System.nanoTime();
        var seqs = unackSeqs;
        var nextAt = unackNextRetryAt;
        var retries = unackRetries;
        var lens = unackLengths;
        var data = unackPayloads;
        int count = 0;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] < 0 || now < nextAt[i]) continue;
            if (retries[i] >= maxRetries) {
                // Retry budget exhausted — drop the packet silently and
                // release the slot. Caller polls exhaustedCount() to detect.
                seqs[i] = -1;
                retries[i] = 0;
                lens[i] = 0;
                unackCount--;
                exhaustedCount++;
                continue;
            }
            buf.putInt(seqs[i]);
            buf.put(data, i * MAX_PAYLOAD_PER_SLOT, lens[i]);
            int nextRetry = retries[i] + 1;
            retries[i] = (byte) nextRetry;
            int shift = nextRetry < MAX_BACKOFF_SHIFT ? nextRetry : MAX_BACKOFF_SHIFT;
            nextAt[i] = now + (baseTimeoutNanos << shift);
            count++;
        }
        return count;
    }

    /**
     * Cumulative number of packets whose retry budget was exhausted since
     * this layer was constructed. Callers checking periodically can
     * request a connection close once this count advances — the higher
     * layer owns that policy.
     */
    public int exhaustedCount() { return exhaustedCount; }

    /**
     * Reset per-connection state on close. Defensive — the class-level
     * javadoc already says one instance per connection, but this lets the
     * framework call onConnectionClose uniformly across all layer types.
     */
    @Override
    public void onConnectionClose(long connectionId) {
        java.util.Arrays.fill(unackSeqs, -1);
        java.util.Arrays.fill(unackRetries, (byte) 0);
        java.util.Arrays.fill(unackLengths, 0);
        unackCount = 0;
        pendingAckSeq = -1;
    }
}
