package jtroop.generate;

import java.lang.classfile.*;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;

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
    }

    private static final ClassDesc CD_Record = ClassDesc.of("java.lang.Record");
    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    private static final ClassDesc CD_GeneratedCodec = ClassDesc.of(
            "jtroop.generate.CodecClassGenerator$GeneratedCodec");

    @SuppressWarnings("unchecked")
    public static GeneratedCodec generate(Class<? extends Record> recordType) {
        MethodHandles.Lookup lookup;
        String className;
        try {
            // Use privateLookupIn so the generated class can access the record type
            lookup = MethodHandles.privateLookupIn(recordType, MethodHandles.lookup());
            // Hidden class must be in the same package as the lookup class
            var pkg = recordType.getPackageName().replace('.', '/');
            className = pkg + "/Codec$" + recordType.getSimpleName();
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot access record type: " + recordType.getName(), e);
        }
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
                            var accessorDesc = MethodTypeDesc.of(classDescFor(rc.getType()));
                            b.invokevirtual(recordDesc, rc.getName(), accessorDesc);

                            // Call ByteBuffer.putXxx
                            emitPut(b, rc.getType());
                            b.pop(); // discard ByteBuffer return
                        }
                        b.return_();
                    });

            // decode(ByteBuffer): Record
            var ctorParamDescs = new ClassDesc[components.length];
            for (int i = 0; i < components.length; i++) {
                ctorParamDescs[i] = classDescFor(components[i].getType());
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
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            return (GeneratedCodec) hiddenClass.lookupClass()
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate codec for " + recordType.getName(), e);
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
        }
    }

    private static ClassDesc classDescFor(Class<?> type) {
        if (type == int.class) return ConstantDescs.CD_int;
        if (type == float.class) return ConstantDescs.CD_float;
        if (type == long.class) return ConstantDescs.CD_long;
        if (type == double.class) return ConstantDescs.CD_double;
        if (type == byte.class) return ConstantDescs.CD_byte;
        if (type == short.class) return ConstantDescs.CD_short;
        if (type == boolean.class) return ConstantDescs.CD_boolean;
        if (type == String.class) return CD_String;
        return ClassDesc.of(type.getName());
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
}
