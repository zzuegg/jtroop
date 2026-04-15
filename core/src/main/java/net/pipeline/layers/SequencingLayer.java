package net.pipeline.layers;

import net.pipeline.Layer;

import java.nio.ByteBuffer;

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
}
