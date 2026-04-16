package jtroop.pipeline;

import jtroop.generate.FusedPipelineGenerator;
import jtroop.generate.FusedPipelineGenerator.FusedPipeline;

import java.nio.ByteBuffer;

/**
 * Ordered chain of {@link Layer}s applied to inbound / outbound bytes.
 *
 * <p>The plain {@link #encodeOutbound}/{@link #decodeInbound} methods loop over
 * a {@code Layer[]} via {@code invokeinterface}, which blocks C2 escape
 * analysis (Rule 4). They remain available for cold paths and tests.
 *
 * <p>Hot paths (server/client TCP read & write) must use {@link #fused()},
 * which returns a generated hidden class calling each concrete layer via
 * {@code invokevirtual} from a monomorphic call site. The fused pipeline is
 * constructed eagerly at {@link Pipeline} creation — same cost profile as
 * the plain loop (one ByteBuffer per layer for the temp chain).
 */
public final class Pipeline {

    private final Layer[] layers;
    private final ByteBuffer[] tempBuffers;
    private final FusedPipeline fused;

    public Pipeline(Layer... layers) {
        this.layers = layers.clone();
        // Pre-allocate temp buffers for pipeline stages (one per layer)
        this.tempBuffers = new ByteBuffer[layers.length];
        for (int i = 0; i < layers.length; i++) {
            tempBuffers[i] = ByteBuffer.allocate(65536);
        }
        this.fused = FusedPipelineGenerator.generate(this.layers);
    }

    /**
     * Monomorphic-dispatch fused pipeline. Server and client hot paths call
     * through this reference so the JIT sees {@code invokevirtual} on the
     * concrete hidden class, which C2 can inline fully for EA.
     */
    public FusedPipeline fused() {
        return fused;
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
