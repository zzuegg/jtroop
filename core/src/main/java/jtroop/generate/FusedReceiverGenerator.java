package jtroop.generate;

import jtroop.codec.CodecRegistry;
import jtroop.pipeline.Layer;
import jtroop.service.Broadcast;
import jtroop.service.OnMessage;
import jtroop.service.Unicast;
import jtroop.service.ZeroAlloc;
import jtroop.session.ConnectionId;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a single hidden class per (pipeline-shape x message-type-set) that fuses
 * the entire receive path — framing, decode, dispatch — into one method. No intermediate
 * Record, no ReadBuffer. The handler receives a freshly-constructed Record whose fields
 * were read directly from the wire ByteBuffer. Because the entire chain — layer decode,
 * field reads, record construction, handler invocation — lives in one generated method,
 * C2 can inline it end-to-end and EA can scalar-replace the Record (it never escapes the
 * inlined handler).
 *
 * <p>The generated class implements {@link FusedReceiver} and holds typed references to:
 * <ul>
 *   <li>Each concrete pipeline layer (monomorphic {@code invokevirtual})</li>
 *   <li>Each handler instance that has eligible bindings</li>
 *   <li>Broadcast and Unicast injectables</li>
 * </ul>
 *
 * <p>The {@code processInbound} method:
 * <ol>
 *   <li>Calls each layer's {@code decodeInbound} in reverse order via invokevirtual on the
 *       concrete type (same as FusedPipelineGenerator)</li>
 *   <li>Peeks the 2-byte type id from the decoded frame</li>
 *   <li>Switches on type id: for each registered message type, reads fields directly from
 *       the ByteBuffer, constructs the Record, and calls the handler method. The Record is
 *       trivially scalar-replaced because the full chain is in one method body.</li>
 *   <li>For unknown type ids: rewinds the frame and returns {@code -1} to signal the caller
 *       to fall back to the 3-stage path for that frame.</li>
 * </ol>
 */
public final class FusedReceiverGenerator {

    /**
     * The fused receiver interface. processInbound replaces the 3-stage
     * pipeline.decode -> codec.decode -> registry.dispatch path.
     *
     * <p>Returns the number of frames dispatched, or {@code -1} if the last
     * frame had an unknown type id (frame position is rewound so the caller
     * can handle it via the fallback path, then call processInbound again
     * for subsequent frames).
     */
    public interface FusedReceiver {
        /**
         * Process frames from {@code wire} until no more complete frames or
         * an unknown type id is encountered.
         *
         * @return number of frames dispatched (>= 0), or -1 if an unknown
         *         type id was encountered (frame is rewound for fallback)
         */
        int processInbound(Layer.Context ctx, ByteBuffer wire, ConnectionId sender);
    }

    /**
     * Descriptor of a single handler method + its message type.
     */
    public record HandlerBinding(
            Object handlerInstance,
            Method method,
            Class<? extends Record> messageType,
            int typeId,
            RecordComponent[] components,
            Class<?>[] parameterTypes
    ) {}

    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_Context = ClassDesc.of("jtroop.pipeline.Layer$Context");
    private static final ClassDesc CD_ConnectionId = ClassDesc.of("jtroop.session.ConnectionId");
    private static final ClassDesc CD_Broadcast = ClassDesc.of("jtroop.service.Broadcast");
    private static final ClassDesc CD_Unicast = ClassDesc.of("jtroop.service.Unicast");
    private static final ClassDesc CD_FusedReceiver = ClassDesc.of(
            "jtroop.generate.FusedReceiverGenerator$FusedReceiver");
    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");

    private static final ConcurrentHashMap<Object, java.lang.reflect.Constructor<?>> CACHE =
            new ConcurrentHashMap<>();

    private FusedReceiverGenerator() {}

    /**
     * Collect all @OnMessage handler bindings from a handler instance, using the
     * CodecRegistry to resolve type ids. Only void-returning, non-@ZeroAlloc
     * handlers with supported field types are eligible.
     */
    public static List<HandlerBinding> collectBindings(Object handlerInstance, CodecRegistry codec) {
        var bindings = new ArrayList<HandlerBinding>();
        for (Method m : handlerInstance.getClass().getDeclaredMethods()) {
            if (!m.isAnnotationPresent(OnMessage.class)) continue;
            if (m.isAnnotationPresent(ZeroAlloc.class)) continue;
            // Only fuse void-returning handlers; response-returning handlers
            // need the sendResponse path which we don't generate.
            if (m.getReturnType() != void.class) continue;

            m.setAccessible(true);

            Class<? extends Record> msgType = null;
            for (var p : m.getParameterTypes()) {
                if (Record.class.isAssignableFrom(p) && p != ConnectionId.class) {
                    @SuppressWarnings("unchecked")
                    var cast = (Class<? extends Record>) p;
                    msgType = cast;
                    break;
                }
            }
            if (msgType == null) continue;

            var components = msgType.getRecordComponents();
            boolean supported = true;
            for (var rc : components) {
                var t = rc.getType();
                if (t != int.class && t != float.class && t != long.class
                        && t != double.class && t != byte.class && t != short.class
                        && t != boolean.class && t != String.class
                        && t != CharSequence.class) {
                    supported = false;
                    break;
                }
            }
            if (!supported) continue;

            codec.register(msgType);
            int typeId = codec.typeId(msgType);
            bindings.add(new HandlerBinding(handlerInstance, m, msgType, typeId,
                    components, m.getParameterTypes()));
        }
        return bindings;
    }

    /**
     * Generate a fused receiver for the given pipeline layers and handler bindings.
     * Supports multiple handler instances (bindings from different handlers).
     */
    public static FusedReceiver generate(Layer[] layers, List<HandlerBinding> bindings,
                                         Broadcast broadcast, Unicast unicast) {
        if (bindings.isEmpty()) return null;

        // Group bindings by handler instance for multi-handler support.
        // Each distinct handler instance gets its own field in the generated class.
        var handlerInstances = new LinkedHashMap<Object, ClassDesc>();
        for (var b : bindings) {
            handlerInstances.computeIfAbsent(b.handlerInstance(),
                    h -> ClassDesc.of(h.getClass().getName()));
        }

        return defineAndInstantiate(layers, bindings, handlerInstances, broadcast, unicast);
    }

    private static FusedReceiver defineAndInstantiate(
            Layer[] layers, List<HandlerBinding> bindings,
            Map<Object, ClassDesc> handlerInstances,
            Broadcast broadcast, Unicast unicast) {

        // Use the first handler's class for the lookup context
        var firstHandler = bindings.getFirst().handlerInstance();
        var firstHandlerClass = firstHandler.getClass();

        var lookup = GeneratorSupport.lookupFor(firstHandlerClass);

        var simpleName = firstHandlerClass.getName()
                .substring(firstHandlerClass.getName().lastIndexOf('.') + 1)
                .replace('$', '_');
        var className = GeneratorSupport.className(firstHandlerClass, "FusedReceiver",
                simpleName + "$" + System.identityHashCode(bindings));

        var thisDesc = ClassDesc.of(className.replace('/', '.'));

        var layerDescs = new ClassDesc[layers.length];
        for (int i = 0; i < layers.length; i++) {
            layerDescs[i] = ClassDesc.of(layers[i].getClass().getName());
        }

        // Assign handler field names: handler0, handler1, ...
        var handlerFieldNames = new LinkedHashMap<Object, String>();
        int idx = 0;
        for (var inst : handlerInstances.keySet()) {
            handlerFieldNames.put(inst, "handler" + idx);
            idx++;
        }

        byte[] bytes = ClassFile.of().build(thisDesc, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(CD_FusedReceiver);

            // Fields: layers, handlers, broadcast, unicast
            for (int i = 0; i < layers.length; i++) {
                cb.withField("layer" + i, layerDescs[i], ACC_PRIVATE | ACC_FINAL);
            }
            for (var entry : handlerInstances.entrySet()) {
                cb.withField(handlerFieldNames.get(entry.getKey()),
                        entry.getValue(), ACC_PRIVATE | ACC_FINAL);
            }
            cb.withField("broadcast", CD_Broadcast, ACC_PRIVATE | ACC_FINAL);
            cb.withField("unicast", CD_Unicast, ACC_PRIVATE | ACC_FINAL);

            // Constructor: (Layer[], Object[] handlers, Broadcast, Unicast)
            var cdLayer = ClassDesc.of("jtroop.pipeline.Layer");
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    cdLayer.arrayType(),
                    ConstantDescs.CD_Object.arrayType(),
                    CD_Broadcast, CD_Unicast);

            cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, b -> {
                b.aload(0);
                b.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                        ConstantDescs.MTD_void);

                for (int i = 0; i < layers.length; i++) {
                    b.aload(0);
                    b.aload(1); // Layer[]
                    b.ldc(i);
                    b.aaload();
                    b.checkcast(layerDescs[i]);
                    b.putfield(thisDesc, "layer" + i, layerDescs[i]);
                }

                int hIdx = 0;
                for (var entry : handlerInstances.entrySet()) {
                    b.aload(0);
                    b.aload(2); // Object[] handlers
                    b.ldc(hIdx);
                    b.aaload();
                    b.checkcast(entry.getValue());
                    b.putfield(thisDesc, handlerFieldNames.get(entry.getKey()),
                            entry.getValue());
                    hIdx++;
                }

                b.aload(0);
                b.aload(3);
                b.putfield(thisDesc, "broadcast", CD_Broadcast);

                b.aload(0);
                b.aload(4);
                b.putfield(thisDesc, "unicast", CD_Unicast);

                b.return_();
            });

            // processInbound(Layer.Context ctx, ByteBuffer wire, ConnectionId sender) -> int
            //   slot 0 = this
            //   slot 1 = ctx (Layer.Context)
            //   slot 2 = wire (ByteBuffer)
            //   slot 3 = sender (ConnectionId)
            //   slot 4 = frame (ByteBuffer, local)
            //   slot 5 = frames count (int, local)
            //   slot 6 = savedWirePos (int, local) — wire.position() before pipeline decode
            var processDesc = MethodTypeDesc.of(ConstantDescs.CD_int,
                    CD_Context, CD_ByteBuffer, CD_ConnectionId);

            cb.withMethodBody("processInbound", processDesc, ACC_PUBLIC, b -> {
                b.iconst_0();
                b.istore(5);

                Label loopTop = b.newLabel();
                Label loopEnd = b.newLabel();

                b.labelBinding(loopTop);

                // Save wire.position() BEFORE pipeline decode so we can rewind
                // if we encounter an unknown typeId.
                b.aload(2);
                b.invokevirtual(CD_ByteBuffer, "position",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
                b.istore(6);

                // Pipeline decode chain (layers in reverse order)
                b.aload(2);
                b.astore(4);

                for (int i = layers.length - 1; i >= 0; i--) {
                    b.aload(0);
                    b.getfield(thisDesc, "layer" + i, layerDescs[i]);
                    b.aload(1); // ctx
                    b.aload(4); // current
                    b.invokevirtual(layerDescs[i], "decodeInbound",
                            MethodTypeDesc.of(CD_ByteBuffer, CD_Context, CD_ByteBuffer));
                    b.astore(4);

                    b.aload(4);
                    b.ifnull(loopEnd);
                }

                // frame in slot 4 is non-null.
                // Read typeId: frame.getShort() & 0xFFFF
                b.aload(4);
                b.invokevirtual(CD_ByteBuffer, "getShort",
                        MethodTypeDesc.of(ConstantDescs.CD_short));
                b.ldc(0xFFFF);
                b.iand();
                // typeId on stack

                Label unknownType = b.newLabel();

                for (int bi = 0; bi < bindings.size(); bi++) {
                    var binding = bindings.get(bi);
                    Label nextCase = b.newLabel();

                    b.dup();
                    b.ldc(binding.typeId());
                    b.if_icmpne(nextCase);
                    b.pop(); // consumed typeId

                    emitDirectDecode(b, thisDesc, handlerInstances.get(binding.handlerInstance()),
                            handlerFieldNames.get(binding.handlerInstance()), binding);

                    b.iinc(5, 1);
                    b.goto_(loopTop);

                    b.labelBinding(nextCase);
                }

                // Unknown type: rewind wire buffer to before the pipeline decoded
                // this frame, so the caller can use the legacy path.
                b.labelBinding(unknownType);
                b.pop(); // pop typeId
                b.aload(2); // wire
                b.iload(6); // savedWirePos
                b.invokevirtual(CD_ByteBuffer, "position",
                        MethodTypeDesc.of(CD_ByteBuffer, ConstantDescs.CD_int));
                b.pop(); // discard ByteBuffer return
                b.iconst_m1();
                b.ireturn();

                b.labelBinding(loopEnd);
                b.iload(5);
                b.ireturn();
            });
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            var ctor = hiddenClass.lookupClass().getDeclaredConstructor(
                    Layer[].class, Object[].class, Broadcast.class, Unicast.class);

            // Build the handler instances array in order
            var handlersArray = handlerFieldNames.keySet().toArray();
            return (FusedReceiver) ctor.newInstance(
                    layers, handlersArray, broadcast, unicast);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate fused receiver for "
                    + firstHandlerClass.getName(), e);
        }
    }

    /**
     * Emit bytecode that reads record fields directly from the ByteBuffer in slot 4,
     * constructs the Record, and calls the handler method.
     */
    private static void emitDirectDecode(CodeBuilder b, ClassDesc thisDesc,
                                          ClassDesc handlerDesc,
                                          String handlerFieldName,
                                          HandlerBinding binding) {
        // Push handler instance
        b.aload(0);
        b.getfield(thisDesc, handlerFieldName, handlerDesc);

        var paramTypes = binding.parameterTypes();
        var components = binding.components();

        for (var paramType : paramTypes) {
            if (paramType == ConnectionId.class) {
                b.aload(3); // sender
            } else if (paramType == Broadcast.class) {
                b.aload(0);
                b.getfield(thisDesc, "broadcast", CD_Broadcast);
            } else if (paramType == Unicast.class) {
                b.aload(0);
                b.getfield(thisDesc, "unicast", CD_Unicast);
            } else if (Record.class.isAssignableFrom(paramType)) {
                emitRecordConstruction(b, binding.messageType(), components);
            }
        }

        // Build target method descriptor
        var targetParamDescs = new ClassDesc[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            targetParamDescs[i] = GeneratorSupport.classDescFor(paramTypes[i]);
        }
        var targetMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, targetParamDescs);

        b.invokevirtual(handlerDesc, binding.method().getName(), targetMethodDesc);
    }

    private static void emitRecordConstruction(CodeBuilder b,
                                                Class<? extends Record> recordType,
                                                RecordComponent[] components) {
        var recordDesc = ClassDesc.of(recordType.getName());
        b.new_(recordDesc);
        b.dup();

        for (var rc : components) {
            emitReadField(b, rc.getType());
        }

        var ctorParamDescs = new ClassDesc[components.length];
        for (int i = 0; i < components.length; i++) {
            ctorParamDescs[i] = GeneratorSupport.classDescFor(components[i].getType());
        }
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamDescs);
        b.invokespecial(recordDesc, ConstantDescs.INIT_NAME, ctorDesc);
    }

    private static void emitReadField(CodeBuilder b, Class<?> type) {
        b.aload(4); // frame ByteBuffer
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
        } else if (type == String.class) {
            b.invokestatic(ClassDesc.of("jtroop.generate.CodecClassGenerator"),
                    "readString",
                    MethodTypeDesc.of(CD_String, CD_ByteBuffer));
        } else if (type == CharSequence.class) {
            b.invokestatic(ClassDesc.of("jtroop.core.ReadBuffer"),
                    "readUtf8CharSequence",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.CharSequence"), CD_ByteBuffer));
        }
    }

}
