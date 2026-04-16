package jtroop.service;

import jtroop.codec.CodecRegistry;
import jtroop.generate.HandlerInvokerGenerator;
import jtroop.generate.HandlerInvokerGenerator.HandlerInvoker;
import jtroop.session.ConnectionId;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;

public final class ServiceRegistry {

    private record HandlerEntry(
            HandlerInvoker invoker,
            Class<? extends Record> messageType,
            boolean hasReturn
    ) {}

    private record LifecycleEntry(Object instance, MethodHandle method) {}

    private final CodecRegistry codec;
    private final Map<Class<? extends Record>, HandlerEntry> handlers = new HashMap<>();
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

            // First parameter that is a Record is the message type
            Class<? extends Record> msgType = null;
            for (var paramType : m.getParameterTypes()) {
                if (Record.class.isAssignableFrom(paramType) && paramType != ConnectionId.class) {
                    msgType = (Class<? extends Record>) paramType;
                    break;
                }
            }
            if (msgType == null) {
                throw new IllegalArgumentException("@OnMessage method " + m.getName()
                        + " has no Record parameter");
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

            boolean hasReturn = m.getReturnType() != void.class;
            // Generate a hidden-class invoker with fixed arity. This avoids the
            // per-dispatch Object[] allocation that MethodHandle.invokeWithArguments
            // requires, and gives C2 a monomorphic invokevirtual that can be inlined
            // and scalar-replaced across.
            var invoker = HandlerInvokerGenerator.generate(handlerInstance, m, msgType);
            handlers.put(msgType, new HandlerEntry(invoker, msgType, hasReturn));
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

    private Broadcast broadcast;
    private Unicast unicast;

    public void setBroadcast(Broadcast broadcast) { this.broadcast = broadcast; }
    public void setUnicast(Unicast unicast) { this.unicast = unicast; }

    public Object dispatch(Record message, ConnectionId sender) {
        var entry = handlers.get(message.getClass());
        if (entry == null) {
            throw new IllegalArgumentException("No handler for message type: " + message.getClass().getName());
        }
        try {
            // Fixed-arity invocation: no Object[] allocated, no MethodHandle boxing.
            // The HandlerInvoker is a hidden class with a monomorphic invokevirtual
            // to the real handler method.
            return entry.invoker().invoke(message, sender, broadcast, unicast);
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
}
