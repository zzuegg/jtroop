package jtroop.generate;

import jtroop.ConfigurationException;
import jtroop.pipeline.Layer;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class that fuses multiple layers into a single concrete class.
 * The generated class calls each layer's encode/decode in sequence via invokevirtual
 * on the concrete layer type (not the Layer interface), ensuring monomorphic dispatch.
 *
 * <p>The layer instances are passed to the constructor and stored as fields.
 *
 * <h2>Shape cache</h2>
 * Pipeline mutation (HTTP→WebSocket upgrade, STARTTLS, etc.) spins up new Pipeline
 * instances whose shape (Layer class sequence) may repeat millions of times across
 * connections. We cache one hidden class per unique shape — keyed by the ordered
 * tuple of concrete {@link Class} values. On hit: reuse the cached constructor,
 * pass in the new layer instances, skip codegen. On miss: emit bytecode, define
 * a hidden class, stash the constructor.
 *
 * <p>Memory bound: one entry per unique shape for the app lifetime. Typical apps
 * have O(10) shapes (plain, framing, framing+encryption, http, websocket, …), not
 * O(connections). No eviction in v1 — document the metaspace cost.
 *
 * <h2>Context parameter (per-connection view)</h2>
 * Each layer call takes a {@link Layer.Context} as the first argument. The
 * generated {@code encodeOutbound} / {@code decodeInbound} methods take the
 * Context in slot 1 and pass it through by {@code aload 1} before each
 * {@code invokevirtual}. One extra bytecode instruction per layer call — no
 * impact on scalar replacement since the Context reference is a plain local.
 */
public final class FusedPipelineGenerator {

    public interface FusedPipeline {
        void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer wire);
        ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire);

        /** Test / cold-path convenience: forwards with a shared no-op Context. */
        default void encodeOutbound(ByteBuffer payload, ByteBuffer wire) {
            encodeOutbound(jtroop.pipeline.LayerContext.NOOP, payload, wire);
        }
        /** Test / cold-path convenience: see {@link #encodeOutbound(ByteBuffer, ByteBuffer)}. */
        default ByteBuffer decodeInbound(ByteBuffer wire) {
            return decodeInbound(jtroop.pipeline.LayerContext.NOOP, wire);
        }
    }

    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_FusedPipeline = ClassDesc.of(
            "jtroop.generate.FusedPipelineGenerator$FusedPipeline");
    private static final ClassDesc CD_Context = ClassDesc.of(
            "jtroop.pipeline.Layer$Context");

    /**
     * Shape key: ordered tuple of concrete {@link Layer} classes. Two Pipelines
     * with identical class sequences share a hidden class even if their layer
     * instances differ — the class takes a {@code Layer[]} and casts each slot.
     */
    private record ShapeKey(Class<?>[] classes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ShapeKey k && Arrays.equals(classes, k.classes);
        }
        @Override
        public int hashCode() {
            return Arrays.hashCode(classes);
        }
    }

    private static final ConcurrentHashMap<ShapeKey, Constructor<?>> CACHE = new ConcurrentHashMap<>();

    /**
     * Cache stats for benchmarking / diagnostics. Plain atomic counters — no
     * effect on the hot path (populated only from {@link #generate}).
     */
    private static final java.util.concurrent.atomic.AtomicLong CACHE_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CACHE_MISSES = new java.util.concurrent.atomic.AtomicLong();

    public static long cacheHits() { return CACHE_HITS.get(); }
    public static long cacheMisses() { return CACHE_MISSES.get(); }
    public static int cacheSize() { return CACHE.size(); }

    public static FusedPipeline generate(Layer... layers) {
        if (layers.length == 0) {
            return new IdentityPipeline();
        }

        var key = shapeKey(layers);
        var cached = CACHE.get(key);
        if (cached != null) {
            CACHE_HITS.incrementAndGet();
            return instantiate(cached, layers);
        }

        // Compute-if-absent so concurrent callers with the same shape share
        // a single codegen. The computeIfAbsent block runs the generator at
        // most once per shape; later callers find the existing entry.
        var ctor = CACHE.computeIfAbsent(key, k -> {
            CACHE_MISSES.incrementAndGet();
            return defineHiddenClass(layers);
        });
        return instantiate(ctor, layers);
    }

    private static ShapeKey shapeKey(Layer[] layers) {
        var classes = new Class<?>[layers.length];
        for (int i = 0; i < layers.length; i++) classes[i] = layers[i].getClass();
        return new ShapeKey(classes);
    }

    private static FusedPipeline instantiate(Constructor<?> ctor, Layer[] layers) {
        try {
            return (FusedPipeline) ctor.newInstance((Object) layers);
        } catch (ReflectiveOperationException e) {
            throw new ConfigurationException("Failed to instantiate fused pipeline", e);
        }
    }

    private static Constructor<?> defineHiddenClass(Layer[] layers) {
        var lookup = MethodHandles.lookup();
        var className = "jtroop/generate/FusedPipeline$" + System.identityHashCode(layers);

        // Build field descriptors for each layer
        var layerDescs = new ClassDesc[layers.length];
        for (int i = 0; i < layers.length; i++) {
            layerDescs[i] = ClassDesc.of(layers[i].getClass().getName());
        }

        byte[] bytes = ClassFile.of().build(ClassDesc.of(className.replace('/', '.')), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(CD_FusedPipeline);

            // Fields for each layer
            for (int i = 0; i < layers.length; i++) {
                cb.withField("layer" + i, layerDescs[i], ACC_PRIVATE | ACC_FINAL);
            }

            // Temp buffer fields for encode (one per layer)
            for (int i = 0; i < layers.length; i++) {
                cb.withField("tmp" + i, CD_ByteBuffer, ACC_PRIVATE | ACC_FINAL);
            }

            // Constructor: takes Layer[] and casts each to concrete type
            var cdLayer = ClassDesc.of("jtroop.pipeline.Layer");
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    cdLayer.arrayType());
            cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, b -> {
                b.aload(0);
                b.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);

                var thisDesc = ClassDesc.of(className.replace('/', '.'));
                for (int i = 0; i < layers.length; i++) {
                    b.aload(0);
                    b.aload(1); // Layer[]
                    b.ldc(i);
                    b.aaload();
                    b.checkcast(layerDescs[i]);
                    b.putfield(thisDesc, "layer" + i, layerDescs[i]);

                    // Allocate temp buffer
                    b.aload(0);
                    b.ldc(65536);
                    b.invokestatic(CD_ByteBuffer, "allocate",
                            MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_int));
                    b.putfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                }
                b.return_();
            });

            // encodeOutbound(Context, ByteBuffer payload, ByteBuffer wire)
            //   slot 1 = Context ctx
            //   slot 2 = ByteBuffer payload
            //   slot 3 = ByteBuffer wire
            var thisDesc = ClassDesc.of(className.replace('/', '.'));
            cb.withMethodBody("encodeOutbound",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Context, CD_ByteBuffer, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        for (int i = 0; i < layers.length; i++) {
                            // tmp[i].clear()
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(ClassDesc.of("java.nio.Buffer"), "clear",
                                    MethodTypeDesc.of(ClassDesc.of("java.nio.Buffer")));
                            b.pop();

                            // layer[i].encodeOutbound(ctx, current, tmp[i])
                            b.aload(0);
                            b.getfield(thisDesc, "layer" + i, layerDescs[i]);
                            b.aload(1); // ctx
                            if (i == 0) {
                                b.aload(2); // payload
                            } else {
                                b.aload(0);
                                b.getfield(thisDesc, "tmp" + (i - 1), CD_ByteBuffer);
                            }
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(layerDescs[i], "encodeOutbound",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Context, CD_ByteBuffer, CD_ByteBuffer));

                            // tmp[i].flip()
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(ClassDesc.of("java.nio.Buffer"), "flip",
                                    MethodTypeDesc.of(ClassDesc.of("java.nio.Buffer")));
                            b.pop();
                        }

                        // wire.put(last tmp)
                        b.aload(3); // wire
                        b.aload(0);
                        b.getfield(thisDesc, "tmp" + (layers.length - 1), CD_ByteBuffer);
                        b.invokevirtual(CD_ByteBuffer, "put",
                                MethodTypeDesc.of(CD_ByteBuffer, CD_ByteBuffer));
                        b.pop();
                        b.return_();
                    });

            // decodeInbound(Context, ByteBuffer wire)
            //   slot 1 = Context ctx
            //   slot 2 = ByteBuffer wire
            //   slot 3 = ByteBuffer current (rolling)
            cb.withMethodBody("decodeInbound",
                    MethodTypeDesc.of(CD_ByteBuffer, CD_Context, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        b.aload(2); // wire → current on stack
                        b.astore(3); // store in slot 3

                        for (int i = layers.length - 1; i >= 0; i--) {
                            b.aload(0);
                            b.getfield(thisDesc, "layer" + i, layerDescs[i]);
                            b.aload(1); // ctx
                            b.aload(3); // current
                            b.invokevirtual(layerDescs[i], "decodeInbound",
                                    MethodTypeDesc.of(CD_ByteBuffer, CD_Context, CD_ByteBuffer));
                            b.astore(3); // new current
                            // null check
                            b.aload(3);
                            var continueLabel = b.newLabel();
                            b.ifnonnull(continueLabel);
                            b.aconst_null();
                            b.areturn();
                            b.labelBinding(continueLabel);
                        }
                        b.aload(3);
                        b.areturn();
                    });
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            return hiddenClass.lookupClass().getDeclaredConstructor(Layer[].class);
        } catch (Exception e) {
            throw new ConfigurationException("Failed to generate fused pipeline", e);
        }
    }

    private static class IdentityPipeline implements FusedPipeline {
        @Override public void encodeOutbound(Layer.Context ctx, ByteBuffer payload, ByteBuffer wire) {
            wire.put(payload);
        }
        @Override public ByteBuffer decodeInbound(Layer.Context ctx, ByteBuffer wire) {
            return wire;
        }
    }
}
