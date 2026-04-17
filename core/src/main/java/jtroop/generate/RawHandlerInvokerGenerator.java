package jtroop.generate;

import jtroop.service.Broadcast;
import jtroop.service.Unicast;
import jtroop.session.ConnectionId;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class per {@code @ZeroAlloc} handler method that implements
 * {@link RawHandlerInvoker}. Mirrors {@link HandlerInvokerGenerator} but the
 * message argument is a raw {@link ByteBuffer} (payload positioned after the
 * type id), not a decoded {@link Record} — no {@code codec.decode} runs for
 * handlers registered this way.
 *
 * <p>Like the record-based invoker, the call site is a monomorphic
 * {@code invokevirtual} on the concrete handler type so C2 can inline and
 * scalar-replace the injectables.
 */
public final class RawHandlerInvokerGenerator {

    /** Zero-alloc handler entry point. Buffer is a reused view; valid only for
     *  the duration of the call. */
    public interface RawHandlerInvoker {
        void invokeRaw(ByteBuffer payload, ConnectionId sender,
                       Broadcast broadcast, Unicast unicast);
    }

    private static final ClassDesc CD_ByteBuffer = ClassDesc.of("java.nio.ByteBuffer");
    private static final ClassDesc CD_ConnectionId = ClassDesc.of("jtroop.session.ConnectionId");
    private static final ClassDesc CD_Broadcast = ClassDesc.of("jtroop.service.Broadcast");
    private static final ClassDesc CD_Unicast = ClassDesc.of("jtroop.service.Unicast");
    private static final ClassDesc CD_RawHandlerInvoker = ClassDesc.of(
            "jtroop.generate.RawHandlerInvokerGenerator$RawHandlerInvoker");

    private RawHandlerInvokerGenerator() {}

    public static RawHandlerInvoker generate(Object handlerInstance, Method method) {
        var handlerClass = handlerInstance.getClass();
        var lookup = GeneratorSupport.lookupFor(handlerClass);

        var simpleOwner = handlerClass.getName()
                .substring(handlerClass.getName().lastIndexOf('.') + 1)
                .replace('$', '_');
        var className = GeneratorSupport.className(handlerClass, "RawHandlerInvoker",
                simpleOwner + "$" + method.getName() + "$" + System.identityHashCode(method));

        var handlerDesc = ClassDesc.of(handlerClass.getName());
        var paramTypes = method.getParameterTypes();

        // Validate: exactly one ByteBuffer param; the rest must be known injectables.
        int bufCount = 0;
        for (var p : paramTypes) {
            if (p == ByteBuffer.class) bufCount++;
            else if (p != ConnectionId.class && p != Broadcast.class && p != Unicast.class) {
                throw new IllegalArgumentException("@ZeroAlloc method " + method.getName()
                        + " has unsupported parameter type: " + p.getName()
                        + " (expected ByteBuffer, ConnectionId, Broadcast, or Unicast)");
            }
        }
        if (bufCount != 1) {
            throw new IllegalArgumentException("@ZeroAlloc method " + method.getName()
                    + " must declare exactly one ByteBuffer parameter, found " + bufCount);
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException("@ZeroAlloc method " + method.getName()
                    + " must return void (raw-buffer handlers do not support response records)");
        }

        // Build target method's descriptor
        var targetParamDescs = new ClassDesc[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            targetParamDescs[i] = ClassDesc.of(paramTypes[i].getName());
        }
        var targetMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, targetParamDescs);

        byte[] bytes = ClassFile.of().build(ClassDesc.of(className.replace('/', '.')), cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(CD_RawHandlerInvoker);

            var thisDesc = ClassDesc.of(className.replace('/', '.'));
            GeneratorSupport.emitHandlerConstructor(cb, thisDesc, handlerDesc);

            // invokeRaw(ByteBuffer, ConnectionId, Broadcast, Unicast): void
            var invokeDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    CD_ByteBuffer, CD_ConnectionId, CD_Broadcast, CD_Unicast);
            cb.withMethodBody("invokeRaw", invokeDesc, ACC_PUBLIC, b -> {
                // push handler
                b.aload(0);
                b.getfield(thisDesc, "handler", handlerDesc);

                // push each target parameter, selecting from injectables
                for (var p : paramTypes) {
                    if (p == ByteBuffer.class) b.aload(1);
                    else if (p == ConnectionId.class) b.aload(2);
                    else if (p == Broadcast.class) b.aload(3);
                    else if (p == Unicast.class) b.aload(4);
                    // else: already validated above
                }

                // monomorphic invokevirtual on concrete handler type
                b.invokevirtual(handlerDesc, method.getName(), targetMethodDesc);
                b.return_();
            });
        });

        return GeneratorSupport.defineAndInstantiate(lookup, bytes,
                RawHandlerInvoker.class, handlerInstance);
    }
}
