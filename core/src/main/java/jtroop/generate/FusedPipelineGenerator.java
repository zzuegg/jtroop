package jtroop.generate;

import jtroop.pipeline.Layer;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class that fuses multiple layers into a single concrete class.
 * The generated class calls each layer's encode/decode in sequence via invokevirtual
 * on the concrete layer type (not the Layer interface), ensuring monomorphic dispatch.
 *
 * The layer instances are passed to the constructor and stored as fields.
 */
public final class FusedPipelineGenerator {

    public interface FusedPipeline {
        void encodeOutbound(ByteBuffer payload, ByteBuffer wire);
        ByteBuffer decodeInbound(ByteBuffer wire);
    }

    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_FusedPipeline = ClassDesc.of(
            "jtroop.generate.FusedPipelineGenerator$FusedPipeline");

    public static FusedPipeline generate(Layer... layers) {
        if (layers.length == 0) {
            return new IdentityPipeline();
        }

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

            // encodeOutbound: calls each layer's encodeOutbound in sequence
            var thisDesc = ClassDesc.of(className.replace('/', '.'));
            cb.withMethodBody("encodeOutbound",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_ByteBuffer, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        // current = payload (slot 1)
                        // wire = slot 2

                        for (int i = 0; i < layers.length; i++) {
                            // tmp[i].clear()
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(ClassDesc.of("java.nio.Buffer"), "clear",
                                    MethodTypeDesc.of(ClassDesc.of("java.nio.Buffer")));
                            b.pop();

                            // layer[i].encodeOutbound(current, tmp[i])
                            b.aload(0);
                            b.getfield(thisDesc, "layer" + i, layerDescs[i]);
                            if (i == 0) {
                                b.aload(1); // payload
                            } else {
                                b.aload(0);
                                b.getfield(thisDesc, "tmp" + (i - 1), CD_ByteBuffer);
                            }
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(layerDescs[i], "encodeOutbound",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_ByteBuffer, CD_ByteBuffer));

                            // tmp[i].flip()
                            b.aload(0);
                            b.getfield(thisDesc, "tmp" + i, CD_ByteBuffer);
                            b.invokevirtual(ClassDesc.of("java.nio.Buffer"), "flip",
                                    MethodTypeDesc.of(ClassDesc.of("java.nio.Buffer")));
                            b.pop();
                        }

                        // wire.put(last tmp)
                        b.aload(2); // wire
                        b.aload(0);
                        b.getfield(thisDesc, "tmp" + (layers.length - 1), CD_ByteBuffer);
                        b.invokevirtual(CD_ByteBuffer, "put",
                                MethodTypeDesc.of(CD_ByteBuffer, CD_ByteBuffer));
                        b.pop();
                        b.return_();
                    });

            // decodeInbound: calls each layer's decodeInbound in reverse
            cb.withMethodBody("decodeInbound",
                    MethodTypeDesc.of(CD_ByteBuffer, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        b.aload(1); // wire → current on stack
                        b.astore(2); // store in slot 2

                        for (int i = layers.length - 1; i >= 0; i--) {
                            b.aload(0);
                            b.getfield(thisDesc, "layer" + i, layerDescs[i]);
                            b.aload(2); // current
                            b.invokevirtual(layerDescs[i], "decodeInbound",
                                    MethodTypeDesc.of(CD_ByteBuffer, CD_ByteBuffer));
                            b.astore(2); // new current
                            // null check
                            b.aload(2);
                            var continueLabel = b.newLabel();
                            b.ifnonnull(continueLabel);
                            b.aconst_null();
                            b.areturn();
                            b.labelBinding(continueLabel);
                        }
                        b.aload(2);
                        b.areturn();
                    });
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            var ctor = hiddenClass.lookupClass().getDeclaredConstructor(Layer[].class);
            return (FusedPipeline) ctor.newInstance((Object) layers);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate fused pipeline", e);
        }
    }

    private static class IdentityPipeline implements FusedPipeline {
        @Override public void encodeOutbound(ByteBuffer payload, ByteBuffer wire) {
            wire.put(payload);
        }
        @Override public ByteBuffer decodeInbound(ByteBuffer wire) {
            return wire;
        }
    }
}
