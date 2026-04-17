package jtroop.generate;

import jtroop.ConfigurationException;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates hidden classes for record codec operations using the java.lang.classfile API.
 * Each generated class has:
 *   - encode(Record, ByteBuffer): writes record fields directly to buffer (no boxing)
 *   - decode(ByteBuffer): reads fields from buffer, calls canonical constructor
 */
public final class CodecClassGenerator {

    public interface GeneratedCodec {
        void encode(Record msg, ByteBuffer buf);
        Record decode(ByteBuffer buf);

        /**
         * Decode fields from the buffer and pass the constructed record to the
         * consumer. If C2 inlines the monomorphic consumer, the record is
         * constructed inside the accept() call and never escapes — enabling
         * scalar replacement (0 B/op).
         *
         * <p>Default implementation falls back to {@link #decode(ByteBuffer)}
         * for codecs generated before this method existed.
         */
        default void decodeConsumer(ByteBuffer buf, Consumer<Record> consumer) {
            consumer.accept(decode(buf));
        }
    }

    private static final ClassDesc CD_Record = ClassDesc.of("java.lang.Record");
    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    private static final ClassDesc CD_CharSequence = ClassDesc.of("java.lang.CharSequence");
    private static final ClassDesc CD_Consumer = ClassDesc.of("java.util.function.Consumer");
    private static final ClassDesc CD_GeneratedCodec = ClassDesc.of(
            "jtroop.generate.CodecClassGenerator$GeneratedCodec");

    @SuppressWarnings("unchecked")
    public static GeneratedCodec generate(Class<? extends Record> recordType) {
        var lookup = GeneratorSupport.lookupFor(recordType);
        var className = GeneratorSupport.className(recordType, "Codec", recordType.getSimpleName());
        var components = recordType.getRecordComponents();
        var recordDesc = ClassDesc.of(recordType.getName());

        byte[] bytes = ClassFile.of().build(ClassDesc.of(className.replace('/', '.')), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(CD_GeneratedCodec);

            // Default constructor
            cb.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ACC_PUBLIC, b -> {
                b.aload(0);
                b.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                b.return_();
            });

            // encode(Record, ByteBuffer)
            cb.withMethodBody("encode",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_Record, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        // Cast Record to specific type
                        b.aload(1); // msg
                        b.checkcast(recordDesc);
                        b.astore(3); // typed msg in slot 3

                        for (RecordComponent rc : components) {
                            b.aload(2); // ByteBuffer
                            b.aload(3); // typed record

                            // Call accessor
                            var accessorDesc = MethodTypeDesc.of(GeneratorSupport.classDescFor(rc.getType()));
                            b.invokevirtual(recordDesc, rc.getName(), accessorDesc);

                            // Call ByteBuffer.putXxx or static helper
                            emitPut(b, rc.getType());
                            // putXxx returns ByteBuffer; static helpers return void.
                            if (rc.getType() != String.class
                                    && rc.getType() != CharSequence.class
                                    && rc.getType() != boolean.class) {
                                b.pop(); // discard ByteBuffer return
                            }
                        }
                        b.return_();
                    });

            // decode(ByteBuffer): Record
            var ctorParamDescs = new ClassDesc[components.length];
            for (int i = 0; i < components.length; i++) {
                ctorParamDescs[i] = GeneratorSupport.classDescFor(components[i].getType());
            }
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamDescs);

            cb.withMethodBody("decode",
                    MethodTypeDesc.of(CD_Record, CD_ByteBuffer),
                    ACC_PUBLIC, b -> {
                        b.new_(recordDesc);
                        b.dup();

                        for (RecordComponent rc : components) {
                            b.aload(1); // ByteBuffer
                            emitGet(b, rc.getType());
                        }

                        b.invokespecial(recordDesc, ConstantDescs.INIT_NAME, ctorDesc);
                        b.areturn();
                    });

            // decodeConsumer(ByteBuffer, Consumer<Record>)
            // Constructs the record and passes it to consumer.accept() in a
            // single call frame. If C2 inlines the monomorphic consumer, the
            // record is constructed on the stack inside accept() and EA can
            // scalar-replace it — 0 B/op.
            cb.withMethodBody("decodeConsumer",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_ByteBuffer, CD_Consumer),
                    ACC_PUBLIC, b -> {
                        // Push consumer first (it's the receiver of accept())
                        b.aload(2); // Consumer

                        // Construct the record: new T(buf.getXxx(), buf.getXxx(), ...)
                        b.new_(recordDesc);
                        b.dup();

                        for (RecordComponent rc : components) {
                            b.aload(1); // ByteBuffer
                            emitGet(b, rc.getType());
                        }

                        b.invokespecial(recordDesc, ConstantDescs.INIT_NAME, ctorDesc);
                        // Stack: [Consumer, Record]
                        b.invokeinterface(CD_Consumer, "accept",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object));
                        b.return_();
                    });
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            return (GeneratedCodec) hiddenClass.lookupClass()
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new ConfigurationException("Failed to generate codec for " + recordType.getName(), e);
        }
    }

    private static void emitPut(CodeBuilder b, Class<?> type) {
        if (type == int.class) {
            b.invokevirtual(CD_ByteBuffer, "putInt",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_int));
        } else if (type == float.class) {
            b.invokevirtual(CD_ByteBuffer, "putFloat",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_float));
        } else if (type == long.class) {
            b.invokevirtual(CD_ByteBuffer, "putLong",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_long));
        } else if (type == double.class) {
            b.invokevirtual(CD_ByteBuffer, "putDouble",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_double));
        } else if (type == byte.class) {
            b.invokevirtual(CD_ByteBuffer, "put",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_byte));
        } else if (type == short.class) {
            b.invokevirtual(CD_ByteBuffer, "putShort",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_short));
        } else if (type == boolean.class) {
            // boolean → byte (1 or 0)
            var afterLabel = b.newLabel();
            var falseLabel = b.newLabel();
            b.ifeq(falseLabel);
            b.aload(2); // need ByteBuffer on stack again
            // Actually this is more complex — let me use a simpler approach
            // Write 1
            b.iconst_1();
            b.i2b();
            b.invokevirtual(CD_ByteBuffer, "put",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_byte));
            b.goto_(afterLabel);
            b.labelBinding(falseLabel);
            b.aload(2);
            b.iconst_0();
            b.i2b();
            b.invokevirtual(CD_ByteBuffer, "put",
                    MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_byte));
            b.labelBinding(afterLabel);
        } else if (type == String.class) {
            // String → getBytes(UTF_8), putShort(len), put(bytes)
            // Too complex for inline bytecode, delegate to helper
            b.invokestatic(ClassDesc.of("jtroop.generate.CodecClassGenerator"),
                    "writeString",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_ByteBuffer, CD_String));
        } else if (type == CharSequence.class) {
            b.invokestatic(ClassDesc.of("jtroop.generate.CodecClassGenerator"),
                    "writeCharSequence",
                    MethodTypeDesc.of(ConstantDescs.CD_void, CD_ByteBuffer, CD_CharSequence));
        }
    }

    private static void emitGet(CodeBuilder b, Class<?> type) {
        if (type == int.class) {
            b.invokevirtual(CD_ByteBuffer, "getInt",
                    MethodTypeDesc.of(ConstantDescs.CD_int));
        } else if (type == float.class) {
            b.invokevirtual(CD_ByteBuffer, "getFloat",
                    MethodTypeDesc.of(ConstantDescs.CD_float));
        } else if (type == long.class) {
            b.invokevirtual(CD_ByteBuffer, "getLong",
                    MethodTypeDesc.of(ConstantDescs.CD_long));
        } else if (type == double.class) {
            b.invokevirtual(CD_ByteBuffer, "getDouble",
                    MethodTypeDesc.of(ConstantDescs.CD_double));
        } else if (type == byte.class) {
            b.invokevirtual(CD_ByteBuffer, "get",
                    MethodTypeDesc.of(ConstantDescs.CD_byte));
        } else if (type == short.class) {
            b.invokevirtual(CD_ByteBuffer, "getShort",
                    MethodTypeDesc.of(ConstantDescs.CD_short));
        } else if (type == boolean.class) {
            b.invokevirtual(CD_ByteBuffer, "get",
                    MethodTypeDesc.of(ConstantDescs.CD_byte));
            // byte → boolean (!=0)
        } else if (type == String.class) {
            b.invokestatic(ClassDesc.of("jtroop.generate.CodecClassGenerator"),
                    "readString",
                    MethodTypeDesc.of(CD_String, CD_ByteBuffer));
        } else if (type == CharSequence.class) {
            b.invokestatic(ClassDesc.of("jtroop.generate.CodecClassGenerator"),
                    "readCharSequence",
                    MethodTypeDesc.of(CD_CharSequence, CD_ByteBuffer));
        }
    }

    // Helper methods called from generated bytecode — delegate to the
    // allocation-free implementations in jtroop.core.{Write,Read}Buffer so the
    // generated-codec path and the reflective-fallback path share a single
    // UTF-8 encoder/decoder. writeUtf8 avoids the intermediate byte[] from
    // String.getBytes(UTF_8); readUtf8 avoids the intermediate byte[len] read
    // buffer via a per-thread scratch.
    public static void writeString(ByteBuffer buf, String value) {
        jtroop.core.WriteBuffer.writeUtf8(buf, value);
    }

    public static String readString(ByteBuffer buf) {
        return jtroop.core.ReadBuffer.readUtf8(buf);
    }

    public static void writeCharSequence(ByteBuffer buf, CharSequence value) {
        jtroop.core.WriteBuffer.writeUtf8CharSequence(buf, value);
    }

    public static CharSequence readCharSequence(ByteBuffer buf) {
        return jtroop.core.ReadBuffer.readUtf8CharSequence(buf);
    }
}
