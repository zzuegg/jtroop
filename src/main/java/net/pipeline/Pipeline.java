package net.pipeline;

import java.nio.ByteBuffer;

public final class Pipeline {

    private final Layer[] layers;

    public Pipeline(Layer... layers) {
        this.layers = layers.clone();
    }

    public void encodeOutbound(ByteBuffer payload, ByteBuffer wire) {
        // Apply layers in reverse order for outbound (innermost first, framing last)
        // For now with single layer, just delegate
        // With multiple layers: payload → layer[0].encode → layer[1].encode → wire
        var current = payload;
        for (int i = 0; i < layers.length; i++) {
            var temp = ByteBuffer.allocate(wire.capacity());
            layers[i].encodeOutbound(current, temp);
            temp.flip();
            current = temp;
        }
        wire.put(current);
    }

    public ByteBuffer decodeInbound(ByteBuffer wire) {
        // Apply layers in order for inbound (framing first, innermost last)
        var current = wire;
        for (int i = layers.length - 1; i >= 0; i--) {
            current = layers[i].decodeInbound(current);
            if (current == null) return null;
        }
        return current;
    }
}
