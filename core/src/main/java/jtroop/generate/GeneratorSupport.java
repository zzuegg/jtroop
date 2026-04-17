package jtroop.generate;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;

import static java.lang.classfile.ClassFile.*;

/**
 * Shared utilities for bytecode generators. All methods here run at startup
 * (class generation time), never on the hot path — extraction has zero JIT
 * impact.
 */
final class GeneratorSupport {

    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    private static final ClassDesc CD_CharSequence = ClassDesc.of("java.lang.CharSequence");

    private GeneratorSupport() {}

    /**
     * Obtain a private lookup for the given target class, suitable for
     * defining hidden classes in the target's package.
     */
    static MethodHandles.Lookup lookupFor(Class<?> targetClass) {
        try {
            return MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "Cannot access class: " + targetClass.getName(), e);
        }
    }

    /**
     * Build a hidden-class name rooted in the target class's package.
     *
     * @param targetClass the class whose package determines the prefix
     * @param tag         short label (e.g. "HandlerInvoker", "Codec")
     * @param suffix      distinguishing suffix (e.g. method name + identity hash)
     * @return a slash-separated internal class name
     */
    static String className(Class<?> targetClass, String tag, String suffix) {
        var pkg = targetClass.getPackageName().replace('.', '/');
        var pkgPrefix = pkg.isEmpty() ? "jtroop/generate" : pkg;
        return pkgPrefix + "/" + tag + "$" + suffix;
    }

    /**
     * Map a Java class to its {@link ClassDesc}, handling all primitives,
     * {@code String}, and {@code CharSequence}. Used by codec and receiver
     * generators to build constructor / accessor descriptors.
     */
    static ClassDesc classDescFor(Class<?> type) {
        if (type == int.class) return ConstantDescs.CD_int;
        if (type == float.class) return ConstantDescs.CD_float;
        if (type == long.class) return ConstantDescs.CD_long;
        if (type == double.class) return ConstantDescs.CD_double;
        if (type == byte.class) return ConstantDescs.CD_byte;
        if (type == short.class) return ConstantDescs.CD_short;
        if (type == boolean.class) return ConstantDescs.CD_boolean;
        if (type == String.class) return CD_String;
        if (type == CharSequence.class) return CD_CharSequence;
        return ClassDesc.of(type.getName());
    }

    /**
     * Emit the standard handler-wrapper constructor:
     * {@code <init>(Object) → this.handler = (HandlerType) arg}.
     * Shared by {@link HandlerInvokerGenerator} and {@link RawHandlerInvokerGenerator}.
     */
    static void emitHandlerConstructor(java.lang.classfile.ClassBuilder cb,
                                       ClassDesc thisDesc,
                                       ClassDesc handlerDesc) {
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object);
        cb.withField("handler", handlerDesc, ACC_PRIVATE | ACC_FINAL);
        cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ACC_PUBLIC, b -> {
            b.aload(0);
            b.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
            b.aload(0);
            b.aload(1);
            b.checkcast(handlerDesc);
            b.putfield(thisDesc, "handler", handlerDesc);
            b.return_();
        });
    }

    /**
     * Define a hidden class from bytecode, instantiate it via its single-arg
     * {@code (Object)} constructor, and return the instance.
     */
    static <T> T defineAndInstantiate(MethodHandles.Lookup lookup, byte[] bytes,
                                      Class<T> iface, Object ctorArg) {
        try {
            var hiddenClass = lookup.defineHiddenClass(bytes, true);
            var ctor = hiddenClass.lookupClass().getDeclaredConstructor(Object.class);
            return iface.cast(ctor.newInstance(ctorArg));
        } catch (Exception e) {
            throw new RuntimeException("Failed to define hidden class for " + iface.getName(), e);
        }
    }
}
