package cn.ac.ios.justin.exec;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.ClassValue;
import cn.ac.ios.justin.core.value.Value.EnumValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Turns Value recipes into live objects inside the sandbox via reflection. */
public final class Materializer {
    private final Sandbox sandbox;

    public Materializer(Sandbox sandbox) { this.sandbox = sandbox; }

    /** Thrown when the recipe itself fails (constructor threw, class missing); distinct from the target failing. */
    public static final class SetupException extends Exception {
        public SetupException(String msg, Throwable cause) { super(msg, cause); }
    }

    public Object materialize(Value v) throws SetupException {
        try {
            if (v instanceof PrimitiveValue p) return p.boxed();
            // interned, as the literal in the emitted test will be: code that compares strings by reference
            // (BooleanUtils.toBoolean(str, trueString, falseString)) must see the same identities in both places
            if (v instanceof StringValue s) return s.value().intern();
            if (v instanceof NullValue) return null;
            if (v instanceof EnumValue e) {
                Class<?> ec = sandbox.load(e.enumClass());
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object c = Enum.valueOf((Class) ec, e.constant());
                return c;
            }
            if (v instanceof ClassValue c) return classFor(c.literal());
            if (v instanceof ArrayValue a) {
                Class<?> comp = classFor(a.component());
                Object arr = Array.newInstance(comp, a.elements().size());
                for (int i = 0; i < a.elements().size(); i++) Array.set(arr, i, materialize(a.elements().get(i)));
                return arr;
            }
            if (v instanceof ObjectValue o) return recipe(o.recipe());
            if (v instanceof Value.FunctionValue f) return function(f);
            if (v instanceof Value.CastValue c) return materialize(c.inner());
            throw new SetupException("unsupported value " + v, null); // VarRef never reaches execution
        } catch (SetupException e) {
            throw e;
        } catch (InvocationTargetException e) {
            throw new SetupException("recipe threw " + e.getTargetException(), e.getTargetException());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new SetupException("cannot materialize " + v + ": " + e, e);
        }
    }

    /** The sandbox counterpart of the lambda the test will contain: same behaviour, stable toString/equals/hashCode. */
    private Object function(Value.FunctionValue f) throws SetupException, ReflectiveOperationException {
        Class<?> iface = classFor(f.iface());
        if (!iface.isInterface()) throw new SetupException("not an interface: " + iface.getName(), null);
        Object constant = f.behavior() == Value.FunctionValue.Behavior.CONSTANT && f.constant() != null ? materialize(f.constant()) : null;
        java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "justin-lambda";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            checkArguments(f, iface, method, args);
            Object result = switch (f.behavior()) {
                case CONSTANT -> constant;
                case IDENTITY -> args != null && args.length > 0 ? args[0] : null;
                case NOOP -> null;
            };
            Class<?> rt = method.getReturnType();
            if (result == null && rt.isPrimitive() && rt != void.class) {
                // a primitive return cannot be null: the zero value of the type
                if (rt == boolean.class) return false;
                if (rt == char.class) return (char) 0;
                if (rt == long.class) return 0L;
                if (rt == double.class) return 0d;
                if (rt == float.class) return 0f;
                if (rt == short.class) return (short) 0;
                if (rt == byte.class) return (byte) 0;
                return 0;
            }
            return result;
        };
        return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader() == null ? sandbox.classLoader() : iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    /**
     * A lambda typed Transformer&lt;Integer, String&gt; throws ClassCastException when it is handed a String
     * (the compiler's bridge method casts); the proxy reproduces that, so sandbox and generated test agree.
     */
    private void checkArguments(Value.FunctionValue f, Class<?> iface, Method method, Object[] args) {
        if (args == null || !(f.iface() instanceof cn.ac.ios.justin.core.type.TypeRef.ClassRef ref) || ref.typeArgs().isEmpty()) return;
        java.lang.reflect.TypeVariable<?>[] vars = iface.getTypeParameters();
        java.lang.reflect.Type[] ptypes = method.getGenericParameterTypes();
        for (int i = 0; i < args.length && i < ptypes.length; i++) {
            if (args[i] == null || !(ptypes[i] instanceof java.lang.reflect.TypeVariable<?> tv)) continue;
            for (int k = 0; k < vars.length && k < ref.typeArgs().size(); k++) {
                if (!vars[k].getName().equals(tv.getName())) continue;
                Class<?> bound;
                try { bound = classFor(ref.typeArgs().get(k)); } catch (ClassNotFoundException | RuntimeException e) { break; }
                if (!bound.isPrimitive() && !bound.isInstance(args[i])) {
                    throw new ClassCastException("class " + args[i].getClass().getName() + " cannot be cast to class " + bound.getName());
                }
            }
        }
    }

    private Object recipe(Recipe r) throws SetupException, ReflectiveOperationException {
        if (r instanceof Recipe.Boxed b) return b.primitive().boxed();
        if (r instanceof Recipe.ConstructorCall cc) {
            Class<?> cls = sandbox.load(cc.className());
            Class<?>[] ptypes = paramTypes(cc.descriptor());
            Constructor<?> ctor = cls.getDeclaredConstructor(ptypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args(cc.args(), ptypes));
        }
        if (r instanceof Recipe.StaticCall sc) {
            Class<?> cls = sandbox.load(sc.className());
            Class<?>[] ptypes = paramTypes(sc.descriptor());
            Method m = cls.getMethod(sc.methodName(), ptypes);
            return m.invoke(null, args(sc.args(), ptypes));
        }
        if (r instanceof Recipe.InstanceCall ic) {
            Object target = materialize(ic.target());
            if (target == null) throw new SetupException("target of " + ic.methodName() + " is null", null);
            Class<?>[] ptypes = paramTypes(ic.descriptor());
            Method m = sandbox.load(ic.declaringClass()).getMethod(ic.methodName(), ptypes);
            return m.invoke(target, args(ic.args(), ptypes));
        }
        if (r instanceof Recipe.Sequence seq) {
            Object obj = recipe(seq.base());
            if (obj == null) throw new SetupException("sequence base produced null", null);
            for (Value.Call call : seq.mutators()) {
                Class<?>[] ptypes = paramTypes(call.descriptor());
                Method m = sandbox.load(call.declaringClass()).getMethod(call.methodName(), ptypes);
                m.invoke(obj, args(call.args(), ptypes));
            }
            return obj;
        }
        throw new SetupException("unsupported recipe " + r, null);
    }

    private Object[] args(List<Value> values, Class<?>[] ptypes) throws SetupException {
        // a single varargs slot (Object[]) receives all values packed into an array
        if (ptypes.length == 1 && ptypes[0].isArray() && ptypes[0].getComponentType() == Object.class && values.size() != 1) {
            Object[] packed = new Object[values.size()];
            for (int i = 0; i < values.size(); i++) packed[i] = materialize(values.get(i));
            return new Object[]{packed};
        }
        if (ptypes.length == 1 && ptypes[0].isArray() && ptypes[0].getComponentType() == Object.class
                && values.size() == 1 && !(values.get(0) instanceof ArrayValue)) {
            return new Object[]{new Object[]{materialize(values.get(0))}};
        }
        Object[] out = new Object[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = materialize(values.get(i));
        return out;
    }

    public Class<?>[] paramTypes(String descriptor) throws ClassNotFoundException {
        List<String> names = Descriptors.parameterTypeNames(descriptor);
        Class<?>[] out = new Class<?>[names.size()];
        for (int i = 0; i < out.length; i++) out[i] = classForName(names.get(i));
        return out;
    }

    public Class<?> classFor(TypeRef t) throws ClassNotFoundException {
        return classForName(t.erasureName());
    }

    /** Resolves "int", "java.lang.String", "int[][]", "pkg.Outer$Inner[]" in the sandbox. */
    public Class<?> classForName(String name) throws ClassNotFoundException {
        int dims = 0;
        while (name.endsWith("[]")) { dims++; name = name.substring(0, name.length() - 2); }
        Class<?> base = switch (name) {
            case "boolean" -> boolean.class; case "byte" -> byte.class; case "char" -> char.class;
            case "short" -> short.class; case "int" -> int.class; case "long" -> long.class;
            case "float" -> float.class; case "double" -> double.class; case "void" -> void.class;
            default -> sandbox.load(name);
        };
        for (int i = 0; i < dims; i++) base = Array.newInstance(base, 0).getClass();
        return base;
    }
}
