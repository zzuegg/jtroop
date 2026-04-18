package jtroop.pipeline.layers;

import jtroop.pipeline.Layer;

import java.nio.ByteBuffer;

/**
 * Prepends a monotonically increasing 4-byte sequence number on encode and
 * drops packets with a seq less-than-or-equal to the last accepted one on decode.
 *
 * <p><b>Per-connection state:</b> {@code sendSeq} and {@code lastReceivedSeq} are
 * per-peer concepts. On the server side a single shared {@code Pipeline} would
 * mean one client's sequence counter interferes with another's — causing legitimate
 * packets to be dropped as "stale". A fresh instance MUST be constructed per
 * connection (e.g. via {@link jtroop.pipeline.Layers#sequencing()} at connection
 * accept time).
 */
public final class SequencingLayer implements Layer {

    private int sendSeq = 0;
    private int lastReceivedSeq = -1;
    /** False until the first packet is accepted; thereafter lastReceivedSeq
     *  is compared modulo 2^32 so sequence rollover is transparent. */
    private boolean anyReceived = false;

    @Override
    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer out) {
        out.putInt(sendSeq++);
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int seq = wire.getInt();
        // Wrap-aware "is seq strictly after lastReceivedSeq modulo 2^32".
        // The unsigned-wrap trick: (seq - lastReceivedSeq) interpreted as a
        // signed int is positive iff seq comes after lastReceivedSeq in the
        // 32-bit wrap window. Handles the MAX_VALUE → MIN_VALUE rollover
        // transparently — pre-fix the signed <= compare dropped every packet
        // after rollover as "stale" and stalled the protocol forever.
        if (anyReceived && (seq - lastReceivedSeq) <= 0) {
            // Stale packet — skip remaining
            wire.position(wire.limit());
            return null;
        }
        lastReceivedSeq = seq;
        anyReceived = true;
        // Return the same buffer, positioned past the 4-byte sequence prefix.
        // Callers read from position..limit so no {@code slice()} allocation
        // is required. {@code wire.getInt()} above already advanced position
        // by 4 bytes — the payload starts there.
        return wire;
    }

    /** Highest sequence number successfully decoded (-1 before any packet). */
    public int lastReceivedSeq() {
        return lastReceivedSeq;
    }

    /** Next sequence number that will be assigned on encode. */
    public int nextSendSeq() {
        return sendSeq;
    }
}
