package jtroop.generate;

import jtroop.service.Broadcast;
import jtroop.service.Unicast;
import jtroop.session.ConnectionId;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class per {@code @OnMessage} handler method that implements
 * {@link HandlerInvoker} with a fixed signature. This enables zero-allocation
 * dispatch: no {@code Object[]} is built for arguments, and the call site becomes
 * a monomorphic {@code invokevirtual} on the concrete handler type that C2 can
 * inline and scalar-replace across.
 *
 * <p>The generated class holds a typed reference to the handler instance and
 * forwards the four injectable values (message, sender, broadcast, unicast) to
 * the real handler method, selecting and ordering arguments as required.
 */
public final class HandlerInvokerGenerator {

    /**
     * Fixed-arity invoker used by {@code ServiceRegistry.dispatch}. The concrete
     * generated classes ignore the parameters that the underlying handler method
     * does not declare.
     */
    public interface HandlerInvoker {
        /** @return handler return value, or {@code null} for void handlers */
        Object invoke(Record message, ConnectionId sender, Broadcast broadcast, Unicast unicast);
    }

    private static final ClassDesc CD_Record = ClassDesc.of("java.lang.Record");
    private static final ClassDesc CD_ConnectionId = ClassDesc.of("jtroop.session.ConnectionId");
    private static final ClassDesc CD_Broadcast = ClassDesc.of("jtroop.service.Broadcast");
    private static final ClassDesc CD_Unicast = ClassDesc.of("jtroop.service.Unicast");
    private static final ClassDesc CD_HandlerInvoker = ClassDesc.of(
            "jtroop.generate.HandlerInvokerGenerator$HandlerInvoker");

    private HandlerInvokerGenerator() {}

    public static HandlerInvoker generate(Object handlerInstance, Method method,
                                          Class<? extends Record> messageType) {
        var handlerClass = handlerInstance.getClass();
        var lookup = GeneratorSupport.lookupFor(handlerClass);

        var simpleOwner = handlerClass.getName()
                .substring(handlerClass.getName().lastIndexOf('.') + 1)
                .replace('$', '_');
        var className = GeneratorSupport.className(handlerClass, "HandlerInvoker",
                simpleOwner + "$" + method.getName() + "$" + System.identityHashCode(method));

        var handlerDesc = ClassDesc.of(handlerClass.getName());
        var messageDesc = ClassDesc.of(messageType.getName());
        var paramTypes = method.getParameterTypes();
        var returnType = method.getReturnType();
        boolean hasReturn = returnType != void.class;

        // Build target method's descriptor
        var targetParamDescs = new ClassDesc[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            targetParamDescs[i] = ClassDesc.of(paramTypes[i].getName());
        }
        ClassDesc returnDesc = hasReturn ? ClassDesc.of(returnType.getName()) : ConstantDescs.CD_void;
        var targetMethodDesc = MethodTypeDesc.of(returnDesc, targetParamDescs);

        byte[] bytes = ClassFile.of().build(ClassDesc.of(className.replace('/', '.')), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(CD_HandlerInvoker);

            var thisDesc = ClassDesc.of(className.replace('/', '.'));
            GeneratorSupport.emitHandlerConstructor(cb, thisDesc, handlerDesc);

            // invoke(Record, ConnectionId, Broadcast, Unicast): Object
            var invokeDesc = MethodTypeDesc.of(ConstantDescs.CD_Object,
                    CD_Record, CD_ConnectionId, CD_Broadcast, CD_Unicast);
            cb.withMethodBody("invoke", invokeDesc, ACC_PUBLIC, b -> {
                // push handler
                b.aload(0);
                b.getfield(thisDesc, "handler", handlerDesc);

                // push each target parameter, selecting from injectables
                for (int i = 0; i < paramTypes.length; i++) {
                    var p = paramTypes[i];
                    if (p == ConnectionId.class) {
                        b.aload(2);
                    } else if (p == Broadcast.class) {
                        b.aload(3);
                    } else if (p == Unicast.class) {
                        b.aload(4);
                    } else if (Record.class.isAssignableFrom(p)) {
                        // message: cast Record -> specific record type
                        b.aload(1);
                        b.checkcast(messageDesc);
                    } else {
                        throw new IllegalArgumentException(
                                "@OnMessage method " + method.getName()
                                        + " has unsupported parameter type: " + p.getName());
                    }
                }

                // invokevirtual on concrete handler type (monomorphic)
                b.invokevirtual(handlerDesc, method.getName(), targetMethodDesc);

                if (hasReturn) {
                    // Return value is a Record (ensured by registration). Already an object.
                    b.areturn();
                } else {
                    b.aconst_null();
                    b.areturn();
                }
            });
        });

        return GeneratorSupport.defineAndInstantiate(lookup, bytes,
                HandlerInvoker.class, handlerInstance);
    }
}
