package net.pipeline.layers;

import net.pipeline.Layer;

import java.nio.ByteBuffer;

public final class FramingLayer implements Layer {

    @Override
    public void encodeOutbound(ByteBuffer payload, ByteBuffer out) {
        out.putInt(payload.remaining());
        out.put(payload);
    }

    @Override
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        if (wire.remaining() < 4) return null;
        wire.mark();
        int length = wire.getInt();
        if (wire.remaining() < length) {
            wire.reset();
            return null;
        }
        var frame = wire.slice(wire.position(), length);
        wire.position(wire.position() + length);
        return frame;
    }
}
