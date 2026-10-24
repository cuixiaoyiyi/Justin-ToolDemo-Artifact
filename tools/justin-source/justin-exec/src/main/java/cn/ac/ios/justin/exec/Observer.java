package cn.ac.ios.justin.exec;

import cn.ac.ios.justin.core.test.Outcome.Observed;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.EnumValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.StringValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Reduces a live result object to an Observed snapshot the oracle can turn into assertions. */
public final class Observer {
    private static final int MAX_ELEMENTS = 32;
    private static final int MAX_DEPTH = 3;

    private Observer() {}

    public static Observed observe(Object o, TypeRef staticType) {
        return observe(o, staticType, 0);
    }

    /** Longest string embedded verbatim in a test; longer results are asserted by length and prefix. */
    public static final int MAX_STRING_LITERAL = 500;

    private static Observed observe(Object o, TypeRef staticType, int depth) {
        if (o == null) return new Observed.Literal(new NullValue(staticType == null ? TypeRef.OBJECT : staticType));
        if (o instanceof String s && s.length() > MAX_STRING_LITERAL) {
            return new Observed.BigString(s.length(), s.substring(0, 64));
        }
        Value lit = literal(o);
        if (lit != null) return new Observed.Literal(lit);
        Class<?> c = o.getClass();
        if (c.isArray()) {
            int n = Array.getLength(o);
            if (n > MAX_ELEMENTS || depth >= MAX_DEPTH) return new Observed.Opaque(c.getName(), null);
            Class<?> comp = c.getComponentType();
            List<Value> elems = new ArrayList<>();
            boolean allLiteral = true;
            List<Observed> obs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Object e = Array.get(o, i);
                Observed oe = observe(e, TypeRef.ClassRef.of(comp.getName()), depth + 1);
                obs.add(oe);
                if (oe instanceof Observed.Literal l) elems.add(l.value()); else allLiteral = false;
            }
            if (allLiteral) return new Observed.Literal(new ArrayValue(typeOf(comp), elems));
            return new Observed.Collection(c.getName(), obs, true);
        }
        // only List and Set have content-based equals; any other Collection stays opaque
        if ((o instanceof List || o instanceof Set) && depth < MAX_DEPTH) {
            Collection<?> col = (Collection<?>) o;
            if (col.size() > MAX_ELEMENTS) return new Observed.Opaque(c.getName(), null);
            List<Observed> obs = new ArrayList<>();
            for (Object e : col) obs.add(observe(e, null, depth + 1));
            return new Observed.Collection(c.getName(), obs, o instanceof List);
        }
        String ts = safeToString(o);
        return new Observed.Opaque(c.getName(), ts);
    }

    /** Value for primitives/wrappers, strings, enums; null if the object is something else. */
    static Value literal(Object o) {
        if (o instanceof Integer v) return PrimitiveValue.of(v);
        if (o instanceof Long v) return PrimitiveValue.of(v);
        if (o instanceof Double v) return PrimitiveValue.of(v);
        if (o instanceof Float v) return PrimitiveValue.of(v);
        if (o instanceof Boolean v) return PrimitiveValue.of(v);
        if (o instanceof Character v) return PrimitiveValue.of(v);
        if (o instanceof Byte v) return PrimitiveValue.of(v);
        if (o instanceof Short v) return PrimitiveValue.of(v);
        if (o instanceof String s) return new StringValue(s);
        if (o instanceof Enum<?> e) return new EnumValue(e.getDeclaringClass().getName(), e.name());
        return null;
    }

    static TypeRef typeOf(Class<?> c) {
        if (c.isPrimitive()) return new TypeRef.Primitive(TypeRef.Kind.fromKeyword(c.getName()));
        if (c.isArray()) return new TypeRef.ArrayRef(typeOf(c.getComponentType()));
        return TypeRef.ClassRef.of(c.getName());
    }

    private static final java.util.regex.Pattern IDENTITY = java.util.regex.Pattern.compile("(?<=[A-Za-z0-9_$\\]])@[0-9a-f]{1,8}\\b");

    /** toString with identity hashes ("Foo@1b6d3586") normalized, so two constructions of the same object compare equal. */
    private static String safeToString(Object o) {
        try {
            String s = String.valueOf(o);
            s = IDENTITY.matcher(s).replaceAll("@");
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Throwable t) {
            return null;
        }
    }
}
