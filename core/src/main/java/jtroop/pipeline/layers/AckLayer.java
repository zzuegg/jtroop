package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

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
 */
public final class AckLayer implements Layer {

    private record UnackedPacket(int seq, byte[] data, long sentAtNanos) {}

    private final long retransmitTimeoutNanos;
    private int sendSeq = 0;
    private final Map<Integer, UnackedPacket> unacked = new LinkedHashMap<>();
    private int lastAckedSeq = -1; // highest acked by peer
    private int pendingAckSeq = -1; // seq to ack back to sender

    public AckLayer() {
        this(200); // default 200ms timeout
    }

    public AckLayer(long retransmitTimeoutMs) {
        this.retransmitTimeoutNanos = retransmitTimeoutMs * 1_000_000L;
    }

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        int seq = sendSeq++;
        out.putInt(seq);
        int start = payload.position();
        out.put(payload);

        // Track for retransmit
        payload.position(start);
        var data = new byte[payload.remaining()];
        payload.get(data);
        unacked.put(seq, new UnackedPacket(seq, data, System.nanoTime()));
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int seq = wire.getInt();
        pendingAckSeq = seq;

        var payload = wire.slice(wire.position(), wire.remaining());
        wire.position(wire.limit());
        return payload;
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
        unacked.remove(ackedSeq);
        if (ackedSeq > lastAckedSeq) {
            lastAckedSeq = ackedSeq;
        }
    }

    /** Number of packets sent but not yet acknowledged. */
    public int unackedCount() {
        return unacked.size();
    }

    /** Whether any unacked packets have exceeded the retransmit timeout. */
    public boolean hasRetransmits() {
        long now = System.nanoTime();
        for (var pkt : unacked.values()) {
            if (now - pkt.sentAtNanos() >= retransmitTimeoutNanos) return true;
        }
        return false;
    }

    /**
     * Write retransmit packets (seq + payload) into the buffer.
     * Returns the number of packets retransmitted.
     */
    public int writeRetransmits(ByteBuffer buf) {
        long now = System.nanoTime();
        int count = 0;
        for (var entry : unacked.entrySet()) {
            var pkt = entry.getValue();
            if (now - pkt.sentAtNanos() >= retransmitTimeoutNanos) {
                buf.putInt(pkt.seq());
                buf.put(pkt.data());
                // Reset timer — setValue() on the existing entry avoids
                // touching the map structurally (no CME, no rehash).
                entry.setValue(new UnackedPacket(pkt.seq(), pkt.data(), now));
                count++;
            }
        }
        return count;
    }
}
