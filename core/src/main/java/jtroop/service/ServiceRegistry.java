package jtroop.service;

import jtroop.ConfigurationException;
import jtroop.JtroopException;
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
    // IdentityHashMap (not HashMap) on the hot-path message-type lookup.
    // Class keys are always identity-compared, so an identity map saves the
    // virtual Class.equals() dispatch inside HashMap.get — small but real
    // savings on the per-dispatch fast path. Safe without synchronisation
    // because registration completes before Server/Client.start() publishes
    // the registry reference, giving readers happens-before via the
    // start-time barrier.
    private final Map<Class<? extends Record>, HandlerInvoker> handlers =
            new java.util.IdentityHashMap<>();
    // Multi-handler support: when multiple handler classes register for the
    // same message type, all invokers are collected here. dispatchAll() fans
    // out to every registered invoker for a given type. The single-handler
    // fast path (dispatch) still uses the primary handlers map above.
    private final Map<Class<? extends Record>, List<HandlerInvoker>> multiHandlers = new HashMap<>();
    // Raw-buffer handlers opt-in via @ZeroAlloc. Indexed by u16 type id for a
    // direct aaload lookup on the inbound hot path (no HashMap, no autoboxing).
    // Size matches CodecRegistry.byId — 65536 refs = 512 KB worst case; unused
    // slots stay null so no allocation cost until first use.
    private final RawHandlerInvoker[] rawHandlersById = new RawHandlerInvoker[65536];
    private final Map<Class<?>, Class<?>> handlerToInterface = new HashMap<>();
    private final Map<Class<?>, Set<Class<? extends Record>>> interfaceToMessageTypes = new HashMap<>();
    private final List<LifecycleEntry> connectHandlers = new ArrayList<>();
    private final List<LifecycleEntry> disconnectHandlers = new ArrayList<>();

    public ServiceRegistry(CodecRegistry codec) {
        this.codec = Objects.requireNonNull(codec, "parameter 'codec' must not be null");
    }

    public void register(Class<?> handlerClass) {
        Objects.requireNonNull(handlerClass, "parameter 'handlerClass' must not be null");
        try {
            var ctor = handlerClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            var instance = ctor.newInstance();
            register(instance);
        } catch (ReflectiveOperationException e) {
            throw new ConfigurationException("Cannot instantiate handler: " + handlerClass.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public void register(Object handlerInstance) {
        Objects.requireNonNull(handlerInstance, "parameter 'handlerInstance' must not be null");
        var handlerClass = handlerInstance.getClass();
        var handles = handlerClass.getAnnotation(Handles.class);
        if (handles == null) {
            throw new ConfigurationException(handlerClass.getName() + " missing @Handles annotation");
        }
        handlerInstanceList.add(handlerInstance);
        var serviceInterface = handles.value();
        handlerToInterface.put(handlerClass, serviceInterface);
        var messageTypes = interfaceToMessageTypes.computeIfAbsent(serviceInterface, _ -> new HashSet<>());

        // Scan handler methods for @OnMessage — walking up the class hierarchy
        // so an @OnMessage declared in a superclass is picked up. Within the
        // walk we deduplicate by (name, parameter types) so a subclass that
        // overrides an annotated superclass method registers only once.
        var lookup = MethodHandles.lookup();
        var seenSignatures = new java.util.HashSet<String>();
        for (Method m : collectAnnotatedMethodsHierarchy(handlerClass, OnMessage.class, seenSignatures)) {
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
                        throw new ConfigurationException(
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
                    throw new ConfigurationException("@OnMessage method " + m.getName()
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
                multiHandlers.computeIfAbsent(msgType, _ -> new ArrayList<>()).add(invoker);
            }
        }

        // Scan for lifecycle methods (also inherited — same rules as @OnMessage).
        var lifecycleSeen = new java.util.HashSet<String>();
        for (Method m : collectAnnotatedMethodsHierarchy(handlerClass, null, lifecycleSeen)) {
            m.setAccessible(true);
            try {
                if (m.isAnnotationPresent(OnConnect.class)) {
                    connectHandlers.add(new LifecycleEntry(handlerInstance, lookup.unreflect(m)));
                }
                if (m.isAnnotationPresent(OnDisconnect.class)) {
                    disconnectHandlers.add(new LifecycleEntry(handlerInstance, lookup.unreflect(m)));
                }
            } catch (IllegalAccessException e) {
                throw new ConfigurationException("Cannot access lifecycle method: " + m.getName(), e);
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
            throw new JtroopException("Raw dispatch failed for type id " + typeId, e);
        }
    }

    /** @return true iff a raw-buffer handler is registered for this type id */
    public boolean hasRawHandler(int typeId) {
        return rawHandlersById[typeId] != null;
    }

    /** @return true iff a record-path handler is registered for this message type */
    public boolean hasHandler(Class<? extends Record> messageType) {
        return handlers.containsKey(messageType);
    }

    /**
     * Fan-out dispatch: invokes ALL registered handlers for the message type.
     * Used by the client where multiple handler classes may register for the
     * same message type. The server's hot path uses {@link #dispatch} which
     * hits the single-handler fast path.
     */
    public void dispatchAll(Record message, ConnectionId sender) {
        var invokers = multiHandlers.get(message.getClass());
        if (invokers == null || invokers.isEmpty()) {
            throw new ConfigurationException("No handler for message type: " + message.getClass().getName());
        }
        for (int i = 0, size = invokers.size(); i < size; i++) {
            try {
                invokers.get(i).invoke(message, sender, broadcast, unicast);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new JtroopException("Dispatch failed for " + message.getClass().getName(), e);
            }
        }
    }

    /**
     * Walk the class hierarchy starting at {@code cls} down to (but not
     * including) {@link Object}, collecting methods. Subclass methods are
     * yielded first so an override shadows the parent version; the
     * {@code seen} set tracks (name, parameter-types) signatures seen so
     * far so the parent's overridden method is skipped.
     *
     * <p>If {@code annotation} is non-null, only methods carrying that
     * annotation are yielded (faster — most methods aren't annotated).
     * If null, every method is yielded.
     */
    private static java.util.List<Method> collectAnnotatedMethodsHierarchy(
            Class<?> cls,
            Class<? extends java.lang.annotation.Annotation> annotation,
            java.util.Set<String> seen) {
        var result = new java.util.ArrayList<Method>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (annotation != null && !m.isAnnotationPresent(annotation)) continue;
                var sig = m.getName() + "(" + java.util.Arrays.stream(m.getParameterTypes())
                        .map(Class::getName)
                        .reduce((a, b) -> a + "," + b).orElse("") + ")";
                if (seen.add(sig)) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    public Object dispatch(Record message, ConnectionId sender) {
        // Hot path: single HashMap lookup, single monomorphic invokevirtual
        // on the hidden-class HandlerInvoker. No per-call allocation —
        // verified by NetGameBenchmark.dispatchDirect_* at ≈ 0 B/op.
        var invoker = handlers.get(message.getClass());
        if (invoker == null) {
            throw new ConfigurationException("No handler for message type: " + message.getClass().getName());
        }
        try {
            return invoker.invoke(message, sender, broadcast, unicast);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new JtroopException("Dispatch failed for " + message.getClass().getName(), e);
        }
    }

    public void dispatchConnect(ConnectionId id) {
        for (var entry : connectHandlers) {
            try {
                entry.method().invoke(entry.instance(), id);
            } catch (Throwable e) {
                throw new JtroopException("OnConnect dispatch failed", e);
            }
        }
    }

    public void dispatchDisconnect(ConnectionId id) {
        for (var entry : disconnectHandlers) {
            try {
                entry.method().invoke(entry.instance(), id);
            } catch (Throwable e) {
                throw new JtroopException("OnDisconnect dispatch failed", e);
            }
        }
    }

    public Class<?> serviceInterface(Class<?> handlerClass) {
        return handlerToInterface.get(handlerClass);
    }

    public Set<Class<? extends Record>> messageTypes(Class<?> serviceInterface) {
        return Collections.unmodifiableSet(
                interfaceToMessageTypes.getOrDefault(serviceInterface, Set.of()));
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
