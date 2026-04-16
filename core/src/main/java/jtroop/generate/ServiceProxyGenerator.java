package jtroop.generate;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class implementing a user-defined service interface and
 * forwarding each method directly to the client's typed {@code send}/{@code request}
 * callbacks. Replaces {@link java.lang.reflect.Proxy}, which allocates an
 * {@code Object[] args} array per invocation and forces reflective dispatch.
 *
 * <p>The generated class takes two targets:
 * <ul>
 *   <li>{@link Consumer} — receives void-returning calls (fire-and-forget)</li>
 *   <li>{@link BiFunction} — receives {@code Record} request-response calls
 *       and returns the typed reply</li>
 * </ul>
 *
 * <p>Each service method becomes a thin stub: push message, invokeinterface on
 * the cached target, (optionally) checkcast and return. Zero per-call
 * allocation; monomorphic call sites inline across.
 */
public final class ServiceProxyGenerator {

    private static final ClassDesc CD_Consumer = ClassDesc.of("java.util.function.Consumer");
    private static final ClassDesc CD_BiFunction = ClassDesc.of("java.util.function.BiFunction");
    private static final ClassDesc CD_Object = ConstantDescs.CD_Object;
    private static final ClassDesc CD_Class = ClassDesc.of("java.lang.Class");

    private ServiceProxyGenerator() {}

    /**
     * Generate and instantiate a proxy for the given service interface.
     *
     * @param serviceInterface interface to implement (all methods must take a
     *                         single {@code Record}-typed argument and return
     *                         either {@code void} or a {@code Record} subtype)
     * @param sendTarget       consumer invoked for void-returning methods
     * @param requestTarget    bi-function invoked for record-returning methods:
     *                         args are (Record message, Class&lt;? extends Record&gt; responseType)
     *                         and must return a Record
     * @param <T> service interface type
     * @return instance implementing {@code serviceInterface}
     */
    @SuppressWarnings("unchecked")
    public static <T> T generate(Class<T> serviceInterface,
                                 Consumer<Record> sendTarget,
                                 BiFunction<Record, Class<?>, Record> requestTarget) {
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "Service must be an interface: " + serviceInterface.getName());
        }

        // The proxy implements serviceInterface, which may be package-private
        // or non-public. Define the hidden class in serviceInterface's own
        // lookup/package so the VM grants access to the superinterface.
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(serviceInterface, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Cannot access service interface: " + serviceInterface.getName(), e);
        }
        var pkg = serviceInterface.getPackageName();
        var pkgPrefix = pkg.isEmpty() ? "jtroop.generate" : pkg;
        var simpleName = serviceInterface.getName()
                .substring(serviceInterface.getName().lastIndexOf('.') + 1)
                .replace('$', '_');
        var className = pkgPrefix + ".ServiceProxy$" + simpleName + "$"
                + System.identityHashCode(serviceInterface);
        var thisDesc = ClassDesc.of(className);
        var ifaceDesc = ClassDesc.of(serviceInterface.getName());

        Method[] methods = serviceInterface.getMethods();

        byte[] bytes = ClassFile.of().build(thisDesc, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withInterfaceSymbols(ifaceDesc);
            cb.withField("send", CD_Consumer, ACC_PRIVATE | ACC_FINAL);
            cb.withField("request", CD_BiFunction, ACC_PRIVATE | ACC_FINAL);
            // A Class<?> field per request-returning method so we don't LDC
            // the class via name at dispatch time (avoids forName path).
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getReturnType() != void.class) {
                    cb.withField("rt" + i, CD_Class, ACC_PRIVATE | ACC_FINAL);
                }
            }

            // Constructor: (Consumer, BiFunction, Class[])
            var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    CD_Consumer, CD_BiFunction, CD_Class.arrayType());
            cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, b -> {
                b.aload(0);
                b.invokespecial(CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                b.aload(0);
                b.aload(1);
                b.putfield(thisDesc, "send", CD_Consumer);
                b.aload(0);
                b.aload(2);
                b.putfield(thisDesc, "request", CD_BiFunction);
                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    if (m.getReturnType() == void.class) continue;
                    b.aload(0);
                    b.aload(3);
                    b.ldc(i);
                    b.aaload();
                    b.putfield(thisDesc, "rt" + i, CD_Class);
                }
                b.return_();
            });

            // One forwarding method per interface method
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (m.getParameterCount() != 1) {
                    throw new IllegalArgumentException(
                            "Service method must take exactly one argument: "
                                    + serviceInterface.getName() + "." + m.getName());
                }
                Class<?> paramType = m.getParameterTypes()[0];
                if (!Record.class.isAssignableFrom(paramType)) {
                    throw new IllegalArgumentException(
                            "Service method argument must be a Record: "
                                    + serviceInterface.getName() + "." + m.getName());
                }
                Class<?> returnType = m.getReturnType();
                boolean hasReturn = returnType != void.class;
                if (hasReturn && !Record.class.isAssignableFrom(returnType)) {
                    throw new IllegalArgumentException(
                            "Service method return must be void or a Record: "
                                    + serviceInterface.getName() + "." + m.getName());
                }

                var paramDesc = ClassDesc.of(paramType.getName());
                var returnDesc = hasReturn
                        ? ClassDesc.of(returnType.getName())
                        : ConstantDescs.CD_void;
                var methodDesc = MethodTypeDesc.of(returnDesc, paramDesc);
                final int methodIndex = i;

                cb.withMethodBody(m.getName(), methodDesc, ACC_PUBLIC, b -> {
                    if (hasReturn) {
                        // return (ReturnType) request.apply(msg, rtN);
                        b.aload(0);
                        b.getfield(thisDesc, "request", CD_BiFunction);
                        b.aload(1); // message
                        b.aload(0);
                        b.getfield(thisDesc, "rt" + methodIndex, CD_Class);
                        b.invokeinterface(CD_BiFunction, "apply",
                                MethodTypeDesc.of(CD_Object, CD_Object, CD_Object));
                        b.checkcast(returnDesc);
                        b.areturn();
                    } else {
                        // send.accept(msg); return;
                        b.aload(0);
                        b.getfield(thisDesc, "send", CD_Consumer);
                        b.aload(1);
                        b.invokeinterface(CD_Consumer, "accept",
                                MethodTypeDesc.of(ConstantDescs.CD_void, CD_Object));
                        b.return_();
                    }
                });
            }
        });

        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true,
                    MethodHandles.Lookup.ClassOption.NESTMATE);
            var ctor = hiddenClass.lookupClass().getDeclaredConstructor(
                    Consumer.class, BiFunction.class, Class[].class);

            Class<?>[] returnTypes = new Class<?>[methods.length];
            for (int i = 0; i < methods.length; i++) {
                returnTypes[i] = methods[i].getReturnType();
            }
            return (T) ctor.newInstance(sendTarget, requestTarget, returnTypes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate service proxy for "
                    + serviceInterface.getName(), e);
        }
    }
}
