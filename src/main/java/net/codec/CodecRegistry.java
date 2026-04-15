package net.codec;

import net.core.ReadBuffer;
import net.core.WriteBuffer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
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
            List<MethodHandle> accessors
    ) {}

    private sealed interface ComponentCodec {
        void encode(Object value, WriteBuffer wb);
        Object decode(ReadBuffer rb);
    }

    private record IntCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeInt((int) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readInt(); }
    }

    private record FloatCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeFloat((float) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readFloat(); }
    }

    private record LongCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeLong((long) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readLong(); }
    }

    private record DoubleCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeDouble((double) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readDouble(); }
    }

    private record ByteCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeByte((byte) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readByte(); }
    }

    private record ShortCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeShort((short) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readShort(); }
    }

    private record StringCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeString((String) value); }
        @Override public Object decode(ReadBuffer rb) { return rb.readString(); }
    }

    private record BooleanCodec() implements ComponentCodec {
        @Override public void encode(Object value, WriteBuffer wb) { wb.writeByte((byte) ((boolean) value ? 1 : 0)); }
        @Override public Object decode(ReadBuffer rb) { return rb.readByte() != 0; }
    }

    private final Map<Class<? extends Record>, CodecEntry> byClass = new HashMap<>();
    private final Map<Integer, CodecEntry> byId = new HashMap<>();
    private int nextId = 0;

    public void register(Class<? extends Record> type) {
        if (byClass.containsKey(type)) return;
        int id = stableTypeId(type);
        var components = buildComponentCodecs(type);
        var constructor = buildConstructor(type);
        var accessors = buildAccessors(type);
        var entry = new CodecEntry(type, id, constructor, components, accessors);
        byClass.put(type, entry);
        byId.put(id, entry);
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
        var entry = byId.get(typeId);
        if (entry == null) throw new IllegalArgumentException("Unknown type id: " + typeId);
        return entry.type();
    }

    public void encode(Record msg, WriteBuffer wb) {
        var entry = byClass.get(msg.getClass());
        if (entry == null) throw new IllegalArgumentException("Unregistered type: " + msg.getClass().getName());
        wb.writeShort((short) entry.typeId());
        for (int i = 0; i < entry.accessors().size(); i++) {
            try {
                Object value = entry.accessors().get(i).invoke(msg);
                entry.components().get(i).encode(value, wb);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to encode component " + i, e);
            }
        }
    }

    public Record decode(ReadBuffer rb) {
        int typeId = rb.readShort() & 0xFFFF;
        var entry = byId.get(typeId);
        if (entry == null) throw new IllegalArgumentException("Unknown type id: " + typeId);
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
