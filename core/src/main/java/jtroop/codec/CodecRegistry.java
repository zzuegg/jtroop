package jtroop.codec;

import jtroop.core.ReadBuffer;
import jtroop.core.WriteBuffer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CodecRegistry {

    private record CodecEntry(
            Class<? extends Record> type,
            int typeId,
            MethodHandle constructor,
            List<ComponentCodec> components,
            List<MethodHandle> accessors,
            jtroop.generate.CodecClassGenerator.GeneratedCodec generatedCodec
    ) {}

    private sealed interface ComponentCodec {
        /**
         * Encode a field directly from a record into a ByteBuffer, avoiding boxing.
         * The accessor MethodHandle reads the field, and the codec writes it.
         */
        void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable;
        Object decode(ReadBuffer rb);
    }

    private record IntCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.putInt((int) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readInt(); }
    }

    private record FloatCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.putFloat((float) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readFloat(); }
    }

    private record LongCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.putLong((long) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readLong(); }
    }

    private record DoubleCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.putDouble((double) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readDouble(); }
    }

    private record ByteCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.put((byte) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readByte(); }
    }

    private record ShortCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.putShort((short) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readShort(); }
    }

    private record StringCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            // Allocation-free: no intermediate byte[] from String.getBytes(UTF_8).
            WriteBuffer.writeUtf8(buf, (String) accessor.invoke(msg));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readString(); }
    }

    private record BooleanCodec() implements ComponentCodec {
        @Override public void encodeDirect(MethodHandle accessor, Record msg, ByteBuffer buf) throws Throwable {
            buf.put((byte) ((boolean) accessor.invoke(msg) ? 1 : 0));
        }
        @Override public Object decode(ReadBuffer rb) { return rb.readByte() != 0; }
    }

    private final Map<Class<? extends Record>, CodecEntry> byClass = new HashMap<>();
    // Direct int-indexed lookup table avoids Integer boxing on the hot decode path.
    // Type IDs are u16 (see stableTypeId) — worst case 65536 slots × 8 B reference = 512 KB.
    // Allocation-free access (no HashMap.get → Integer.valueOf), monomorphic aaload.
    private final CodecEntry[] byId = new CodecEntry[65536];
    private int nextId = 0;

    public void register(Class<? extends Record> type) {
        if (byClass.containsKey(type)) return;
        int id = stableTypeId(type);
        var components = buildComponentCodecs(type);
        var constructor = buildConstructor(type);
        var accessors = buildAccessors(type);
        // Try to generate bytecode codec; fall back to reflection if it fails
        jtroop.generate.CodecClassGenerator.GeneratedCodec generated = null;
        try {
            generated = jtroop.generate.CodecClassGenerator.generate(type);
        } catch (Exception _) {
            // Fall back to MethodHandle-based codec
        }
        var entry = new CodecEntry(type, id, constructor, components, accessors, generated);
        byClass.put(type, entry);
        byId[id] = entry;
    }

    private static int stableTypeId(Class<?> type) {
        // Deterministic ID from class name — same on client and server
        return type.getName().hashCode() & 0xFFFF;
    }

    public int typeId(Class<? extends Record> type) {
        var entry = byClass.get(type);
        if (entry == null) throw new IllegalArgumentException("Unregistered type: " + type.getName());
        return entry.typeId();
    }

    public Class<? extends Record> classForTypeId(int typeId) {
        var entry = byId[typeId];
        if (entry == null) throw new IllegalArgumentException("Unknown type id: " + typeId);
        return entry.type();
    }

    @SuppressWarnings("unchecked")
    public void encode(Record msg, WriteBuffer wb) {
        var entry = byClass.get(msg.getClass());
        if (entry == null) {
            register((Class<? extends Record>) msg.getClass());
            entry = byClass.get(msg.getClass());
        }
        var buf = wb.buffer();
        buf.putShort((short) entry.typeId());
        if (entry.generatedCodec() != null) {
            entry.generatedCodec().encode(msg, buf);
        } else {
            for (int i = 0; i < entry.accessors().size(); i++) {
                try {
                    entry.components().get(i).encodeDirect(entry.accessors().get(i), msg, buf);
                } catch (Throwable e) {
                    throw new RuntimeException("Failed to encode component " + i, e);
                }
            }
        }
    }

    public Record decode(ReadBuffer rb) {
        // Read ByteBuffer once into a local — lets EA inline the generated codec call
        // without re-dereferencing ReadBuffer.buf on every field access.
        var buf = rb.buffer();
        int typeId = buf.getShort() & 0xFFFF;
        var entry = byId[typeId];
        if (entry == null) throw new IllegalArgumentException("Unknown type id: " + typeId);
        var generated = entry.generatedCodec();
        if (generated != null) {
            return generated.decode(buf);
        }
        var args = new Object[entry.components().size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = entry.components().get(i).decode(rb);
        }
        try {
            return (Record) entry.constructor().invokeWithArguments(args);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to construct " + entry.type().getName(), e);
        }
    }

    /**
     * Look up the pre-generated hidden-class codec for a specific record type.
     * Used by hot paths that already know the expected type (e.g. request/response
     * with a known response class) to bypass the {@code byId[typeId]} lookup and
     * the typeId read. Returning the concrete codec keeps the subsequent
     * {@code .decode(buf)} callsite monomorphic, letting C2 inline end-to-end
     * and EA scalar-replace the returned record.
     *
     * @return the generated codec, or {@code null} if reflective-fallback is used
     *         (happens only for types that failed bytecode generation).
     */
    public jtroop.generate.CodecClassGenerator.GeneratedCodec generatedCodecFor(
            Class<? extends Record> type) {
        var entry = byClass.get(type);
        if (entry == null) throw new IllegalArgumentException("Unregistered type: " + type.getName());
        return entry.generatedCodec();
    }

    private List<ComponentCodec> buildComponentCodecs(Class<? extends Record> type) {
        var result = new ArrayList<ComponentCodec>();
        for (RecordComponent rc : type.getRecordComponents()) {
            result.add(codecFor(rc.getType()));
        }
        return List.copyOf(result);
    }

    private ComponentCodec codecFor(Class<?> type) {
        if (type == int.class || type == Integer.class) return new IntCodec();
        if (type == float.class || type == Float.class) return new FloatCodec();
        if (type == long.class || type == Long.class) return new LongCodec();
        if (type == double.class || type == Double.class) return new DoubleCodec();
        if (type == byte.class || type == Byte.class) return new ByteCodec();
        if (type == short.class || type == Short.class) return new ShortCodec();
        if (type == boolean.class || type == Boolean.class) return new BooleanCodec();
        if (type == String.class) return new StringCodec();
        throw new IllegalArgumentException("Unsupported component type: " + type.getName());
    }

    private List<MethodHandle> buildAccessors(Class<? extends Record> type) {
        var result = new ArrayList<MethodHandle>();
        var lookup = MethodHandles.lookup();
        for (RecordComponent rc : type.getRecordComponents()) {
            try {
                var accessor = rc.getAccessor();
                accessor.setAccessible(true);
                result.add(lookup.unreflect(accessor));
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access component: " + rc.getName(), e);
            }
        }
        return List.copyOf(result);
    }

    private MethodHandle buildConstructor(Class<? extends Record> type) {
        var components = type.getRecordComponents();
        var paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
        }
        try {
            var ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(ctor);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot find canonical constructor for " + type.getName(), e);
        }
    }
}
