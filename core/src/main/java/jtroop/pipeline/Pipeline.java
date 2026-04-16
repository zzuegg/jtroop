package jtroop.pipeline;

import jtroop.generate.FusedPipelineGenerator;
import jtroop.generate.FusedPipelineGenerator.FusedPipeline;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Ordered chain of {@link Layer}s applied to inbound / outbound bytes.
 *
 * <p>The plain {@link #encodeOutbound}/{@link #decodeInbound} methods loop over
 * a {@code Layer[]} via {@code invokeinterface}, which blocks C2 escape
 * analysis (Rule 4). They remain available for cold paths and tests.
 *
 * <p>Hot paths (server/client TCP read &amp; write) must use {@link #fused()},
 * which returns a generated hidden class calling each concrete layer via
 * {@code invokevirtual} from a monomorphic call site. The fused pipeline is
 * constructed eagerly at {@link Pipeline} creation — same cost profile as
 * the plain loop (one ByteBuffer per layer for the temp chain).
 *
 * <h2>Mutation</h2>
 * Pipelines are <em>immutable</em>. To change the layer stack at runtime
 * (HTTP→WebSocket upgrade, STARTTLS, ALPN, PROXY protocol, …) the mutation
 * methods {@link #addFirst}, {@link #addLast}, {@link #remove},
 * {@link #replace} return a <b>new</b> Pipeline instance. The caller then
 * hands the new pipeline to {@code Server.switchPipeline(connId, newPipeline)}
 * or {@code Client.switchPipeline(newPipeline)} — a cold event (once per
 * connection lifetime) so the cost of building a new Pipeline (and
 * potentially a new fused hidden class on shape-cache miss) is acceptable.
 *
 * <p>Hidden-class regeneration is <b>shape-indexed and cached</b>: two
 * pipelines with the same ordered sequence of concrete Layer classes share
 * a hidden class. See {@link FusedPipelineGenerator} for cache semantics.
 *
 * <p><b>Stateful-layer warning.</b> Layers such as {@code FramingLayer}
 * (cached view), {@code AckLayer} (unacked SoA), {@code EncryptionLayer}
 * (cipher nonce), and {@code HttpLayer} (keep-alive, HEAD flag) hold
 * per-connection state. If the new pipeline contains a <em>different
 * instance</em> of the same layer type, that state is gone. If you need to
 * preserve state, reuse the existing instance: {@code pipeline.replace(
 * HttpLayer.class, websocketLayer)} keeps the other layers but installs the
 * new one. {@code pipeline.addFirst(new TlsLayer())} preserves all prior
 * instances.
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

    /**
     * Number of layers in this pipeline.
     */
    public int size() {
        return layers.length;
    }

    /**
     * Defensive copy of the layer array. Intended for diagnostics and
     * mutation helpers — not for hot-path iteration.
     */
    public Layer[] layers() {
        return layers.clone();
    }

    public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer wire) {
        var current = payload;
        for (int i = 0; i < layers.length; i++) {
            tempBuffers[i].clear();
            layers[i].encodeOutbound(ctx, current, tempBuffers[i]);
            tempBuffers[i].flip();
            current = tempBuffers[i];
        }
        wire.put(current);
    }

    public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
        var current = wire;
        for (int i = layers.length - 1; i >= 0; i--) {
            current = layers[i].decodeInbound(ctx, current);
            if (current == null) return null;
        }
        return current;
    }

    /** Convenience for cold paths / tests that don't have a Context. */
    public void encodeOutbound(ByteBuffer payload, ByteBuffer wire) {
        encodeOutbound(LayerContext.NOOP, payload, wire);
    }

    /** Convenience for cold paths / tests that don't have a Context. */
    public ByteBuffer decodeInbound(ByteBuffer wire) {
        return decodeInbound(LayerContext.NOOP, wire);
    }

    // ------------------------------------------------------------------
    // Mutation helpers — all return a new Pipeline. See class-level javadoc
    // for the stateful-layer warning.
    // ------------------------------------------------------------------

    /**
     * Return a new Pipeline with {@code layer} prepended (encoded last,
     * decoded first). Typical use: install a TLS layer at the outer edge
     * after STARTTLS negotiation.
     */
    public Pipeline addFirst(Layer layer) {
        var out = new Layer[layers.length + 1];
        out[0] = layer;
        System.arraycopy(layers, 0, out, 1, layers.length);
        return new Pipeline(out);
    }

    /**
     * Return a new Pipeline with {@code layer} appended (encoded first,
     * decoded last). Typical use: bolt on a codec above an existing framing
     * stack.
     */
    public Pipeline addLast(Layer layer) {
        var out = Arrays.copyOf(layers, layers.length + 1);
        out[layers.length] = layer;
        return new Pipeline(out);
    }

    /**
     * Return a new Pipeline with the first layer of {@code layerType}
     * removed. If no layer of that type exists the current Pipeline is
     * returned unchanged.
     */
    public Pipeline remove(Class<? extends Layer> layerType) {
        int idx = indexOf(layerType);
        if (idx < 0) return this;
        var out = new Layer[layers.length - 1];
        System.arraycopy(layers, 0, out, 0, idx);
        System.arraycopy(layers, idx + 1, out, idx, layers.length - idx - 1);
        return new Pipeline(out);
    }

    /**
     * Return a new Pipeline with the first layer of {@code oldType} replaced
     * by {@code newLayer}. Preserves position in the stack. Throws
     * {@link IllegalArgumentException} if no layer of {@code oldType} is
     * present — replacing a non-existent layer is almost always a bug (use
     * {@link #addFirst}/{@link #addLast} for insertion).
     */
    public Pipeline replace(Class<? extends Layer> oldType, Layer newLayer) {
        int idx = indexOf(oldType);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "No layer of type " + oldType.getName() + " in pipeline");
        }
        var out = layers.clone();
        out[idx] = newLayer;
        return new Pipeline(out);
    }

    private int indexOf(Class<? extends Layer> layerType) {
        for (int i = 0; i < layers.length; i++) {
            if (layers[i].getClass() == layerType) return i;
        }
        return -1;
    }
}
