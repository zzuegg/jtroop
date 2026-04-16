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

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        out.putInt(sendSeq++);
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        int seq = wire.getInt();
        if (seq <= lastReceivedSeq) {
            // Stale packet — skip remaining
            wire.position(wire.limit());
            return null;
        }
        lastReceivedSeq = seq;
        // Return remaining as payload
        var payload = wire.slice(wire.position(), wire.remaining());
        wire.position(wire.limit());
        return payload;
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
