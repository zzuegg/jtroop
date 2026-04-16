package jtroop.service;

import jtroop.codec.CodecRegistry;
import jtroop.generate.HandlerInvokerGenerator;
import jtroop.generate.HandlerInvokerGenerator.HandlerInvoker;
import jtroop.generate.RawHandlerInvokerGenerator;
import jtroop.generate.RawHandlerInvokerGenerator.RawHandlerInvoker;
import jtroop.session.ConnectionId;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.*;

public final class ServiceRegistry {

    private record LifecycleEntry(Object instance, MethodHandle method) {}

    private final CodecRegistry codec;
    // Hot path: dispatch() looks up the invoker by message class. Storing the
    // HandlerInvoker directly (rather than a wrapping record) drops one method
    // call and one field read per dispatch — keeps the path small enough for
    // C2 to inline the full chain and scalar-replace injectables (CLAUDE.md
    // rule #3).
    private final Map<Class<? extends Record>, HandlerInvoker> handlers = new HashMap<>();
    // Raw-buffer handlers opt-in via @ZeroAlloc. Indexed by u16 type id for a
    // direct aaload lookup on the inbound hot path (no HashMap, no autoboxing).
    // Size matches CodecRegistry.byId — 65536 refs = 512 KB worst case; unused
    // slots stay null so no allocation cost until first use.
    private final RawHandlerInvoker[] rawHandlersById = new RawHandlerInvoker[65536];
    private final Map<Class<?>, Class<?>> handlerToInterface = new HashMap<>();
    private final Map<Class<?>, Set<Class<? extends Record>>> interfaceToMessageTypes = new HashMap<>();
    private final Set<Class<? extends Record>> datagramTypes = new HashSet<>();
    private final List<LifecycleEntry> connectHandlers = new ArrayList<>();
    private final List<LifecycleEntry> disconnectHandlers = new ArrayList<>();

    public ServiceRegistry(CodecRegistry codec) {
        this.codec = codec;
    }

    public void register(Class<?> handlerClass) {
        try {
            var ctor = handlerClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            var instance = ctor.newInstance();
            register(instance);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot instantiate handler: " + handlerClass.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public void register(Object handlerInstance) {
        var handlerClass = handlerInstance.getClass();
        var handles = handlerClass.getAnnotation(Handles.class);
        if (handles == null) {
            throw new IllegalArgumentException(handlerClass.getName() + " missing @Handles annotation");
        }
        handlerInstanceList.add(handlerInstance);
        var serviceInterface = handles.value();
        handlerToInterface.put(handlerClass, serviceInterface);
        var messageTypes = interfaceToMessageTypes.computeIfAbsent(serviceInterface, _ -> new HashSet<>());

        // Scan service interface for @Datagram annotations
        var datagramMethods = new HashSet<String>();
        for (Method m : serviceInterface.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Datagram.class)) {
                datagramMethods.add(m.getName());
            }
        }

        // Scan handler methods for @OnMessage
        var lookup = MethodHandles.lookup();
        for (Method m : handlerClass.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(OnMessage.class)) continue;
            m.setAccessible(true);

            // Two dispatch shapes depending on @ZeroAlloc:
            //  - Record-param shape (default): decode record, invoke handler.
            //  - @ZeroAlloc shape: skip decode, pass raw ByteBuffer payload.
            var zeroAlloc = m.getAnnotation(ZeroAlloc.class);

            Class<? extends Record> msgType;
            if (zeroAlloc != null) {
                msgType = zeroAlloc.value();
                // Reject a Record parameter on a @ZeroAlloc method — would be
                // ambiguous with the record-path handler. The raw-buffer
                // generator validates the required ByteBuffer + injectable
                // types further.
                for (var paramType : m.getParameterTypes()) {
                    if (Record.class.isAssignableFrom(paramType) && paramType != ConnectionId.class) {
                        throw new IllegalArgumentException(
                                "@ZeroAlloc method " + m.getName()
                                        + " must not declare a Record parameter; takes ByteBuffer instead");
                    }
                }
            } else {
                // First parameter that is a Record is the message type
                Class<? extends Record> found = null;
                for (var paramType : m.getParameterTypes()) {
                    if (Record.class.isAssignableFrom(paramType) && paramType != ConnectionId.class) {
                        found = (Class<? extends Record>) paramType;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalArgumentException("@OnMessage method " + m.getName()
                            + " has no Record parameter");
                }
                msgType = found;
            }

            codec.register(msgType);
            messageTypes.add(msgType);

            // Register return type if it's a Record (response type)
            if (Record.class.isAssignableFrom(m.getReturnType()) && m.getReturnType() != void.class) {
                codec.register((Class<? extends Record>) m.getReturnType());
            }

            // Check if this method name matches a @Datagram method on the interface
            if (datagramMethods.contains(m.getName())) {
                datagramTypes.add(msgType);
            }

            if (zeroAlloc != null) {
                // Hidden-class raw invoker: monomorphic invokevirtual on the
                // concrete handler; no record/String allocation on decode.
                var rawInvoker = RawHandlerInvokerGenerator.generate(handlerInstance, m);
                rawHandlersById[codec.typeId(msgType)] = rawInvoker;
            } else {
                // Generate a hidden-class invoker with fixed arity. This avoids the
                // per-dispatch Object[] allocation that MethodHandle.invokeWithArguments
                // requires, and gives C2 a monomorphic invokevirtual that can be inlined
                // and scalar-replaced across.
                var invoker = HandlerInvokerGenerator.generate(handlerInstance, m, msgType);
                handlers.put(msgType, invoker);
            }
        }

        // Scan for lifecycle methods
        for (Method m : handlerClass.getDeclaredMethods()) {
            m.setAccessible(true);
            try {
                if (m.isAnnotationPresent(OnConnect.class)) {
                    connectHandlers.add(new LifecycleEntry(handlerInstance, lookup.unreflect(m)));
                }
                if (m.isAnnotationPresent(OnDisconnect.class)) {
                    disconnectHandlers.add(new LifecycleEntry(handlerInstance, lookup.unreflect(m)));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access lifecycle method: " + m.getName(), e);
            }
        }
    }

    // Default to the shared no-op singletons so a handler declaring a
    // Broadcast/Unicast parameter never sees null, and so dispatch never
    // needs a null-check. setBroadcast/setUnicast swaps them for the real
    // Server-backed impls during wire-up.
    private Broadcast broadcast = Broadcast.NO_OP;
    private Unicast unicast = Unicast.NO_OP;

    public void setBroadcast(Broadcast broadcast) {
        this.broadcast = broadcast == null ? Broadcast.NO_OP : broadcast;
    }
    public void setUnicast(Unicast unicast) {
        this.unicast = unicast == null ? Unicast.NO_OP : unicast;
    }

    /**
     * Zero-alloc dispatch entry point. Routes a frame by type id to a
     * {@code @ZeroAlloc} raw-buffer handler if one is registered. Returns
     * {@code true} if the raw handler ran; {@code false} if the caller should
     * fall back to the standard decode + {@link #dispatch} path.
     *
     * <p>The handler runs inline on the caller thread — no executor handoff —
     * so the payload buffer (which is a reused view over the connection's read
     * buffer) stays valid for the duration of the call.
     */
    public boolean dispatchRaw(int typeId, ByteBuffer payload, ConnectionId sender) {
        var raw = rawHandlersById[typeId];
        if (raw == null) return false;
        try {
            raw.invokeRaw(payload, sender, broadcast, unicast);
            return true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Raw dispatch failed for type id " + typeId, e);
        }
    }

    /** @return true iff a raw-buffer handler is registered for this type id */
    public boolean hasRawHandler(int typeId) {
        return rawHandlersById[typeId] != null;
    }

    public Object dispatch(Record message, ConnectionId sender) {
        // Hot path: single HashMap lookup, single monomorphic invokevirtual
        // on the hidden-class HandlerInvoker. No per-call allocation —
        // verified by NetGameBenchmark.dispatchDirect_* at ≈ 0 B/op.
        var invoker = handlers.get(message.getClass());
        if (invoker == null) {
            throw new IllegalArgumentException("No handler for message type: " + message.getClass().getName());
        }
        try {
            return invoker.invoke(message, sender, broadcast, unicast);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Dispatch failed for " + message.getClass().getName(), e);
        }
    }

    public void dispatchConnect(ConnectionId id) {
        for (var entry : connectHandlers) {
            try {
                entry.method().invoke(entry.instance(), id);
            } catch (Throwable e) {
                throw new RuntimeException("OnConnect dispatch failed", e);
            }
        }
    }

    public void dispatchDisconnect(ConnectionId id) {
        for (var entry : disconnectHandlers) {
            try {
                entry.method().invoke(entry.instance(), id);
            } catch (Throwable e) {
                throw new RuntimeException("OnDisconnect dispatch failed", e);
            }
        }
    }

    public boolean isDatagram(Class<? extends Record> messageType) {
        return datagramTypes.contains(messageType);
    }

    public Class<?> serviceInterface(Class<?> handlerClass) {
        return handlerToInterface.get(handlerClass);
    }

    public Set<Class<? extends Record>> messageTypes(Class<?> serviceInterface) {
        return Collections.unmodifiableSet(
                interfaceToMessageTypes.getOrDefault(serviceInterface, Set.of()));
    }

    public boolean hasHandler(Class<? extends Record> messageType) {
        return handlers.containsKey(messageType);
    }

    public CodecRegistry codec() {
        return codec;
    }

    /** Returns all registered handler instances (distinct). Used by the fused
     *  receiver generator to collect @OnMessage bindings at build time. */
    public List<Object> handlerInstances() {
        // Collect unique handler instances from the generated invokers.
        // Each HandlerInvoker wraps a single handler instance — we need the
        // originals for reflection scanning.
        return List.copyOf(handlerInstanceList);
    }

    // Maintained alongside handlers map during register(Object).
    private final List<Object> handlerInstanceList = new ArrayList<>();
}
