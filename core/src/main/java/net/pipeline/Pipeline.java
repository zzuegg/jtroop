package net.pipeline;

import java.nio.ByteBuffer;

public final class Pipeline {

    private final Layer[] layers;
    private final ByteBuffer[] tempBuffers;

    public Pipeline(Layer... layers) {
        this.layers = layers.clone();
        // Pre-allocate temp buffers for pipeline stages (one per layer)
        this.tempBuffers = new ByteBuffer[layers.length];
        for (int i = 0; i < layers.length; i++) {
            tempBuffers[i] = ByteBuffer.allocate(65536);
        }
    }

    public void encodeOutbound(ByteBuffer payload, ByteBuffer wire) {
        var current = payload;
        for (int i = 0; i < layers.length; i++) {
            tempBuffers[i].clear();
            layers[i].encodeOutbound(current, tempBuffers[i]);
            tempBuffers[i].flip();
            current = tempBuffers[i];
        }
        wire.put(current);
    }

    public ByteBuffer decodeInbound(ByteBuffer wire) {
        var current = wire;
        for (int i = layers.length - 1; i >= 0; i--) {
            current = layers[i].decodeInbound(current);
            if (current == null) return null;
        }
        return current;
    }
}
