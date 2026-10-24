package cn.ac.ios.justin.generics;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.core.type.TypeRef.Wildcard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaces type variables by name using a binding environment. Unbound variables are left as-is. */
public final class TypeSubstitution {
    private TypeSubstitution() {}

    public static TypeRef substitute(TypeRef t, Map<String, TypeRef> bindings) {
        if (bindings.isEmpty()) return t;
        if (t instanceof TypeVar v) {
            TypeRef b = bindings.get(v.name());
            return b != null ? b : t;
        }
        if (t instanceof ClassRef c) {
            if (c.typeArgs().isEmpty()) return c;
            List<TypeRef> args = new ArrayList<>(c.typeArgs().size());
            for (TypeRef a : c.typeArgs()) args.add(substitute(a, bindings));
            return new ClassRef(c.binaryName(), args);
        }
        if (t instanceof ArrayRef a) return new ArrayRef(substitute(a.component(), bindings));
        if (t instanceof Wildcard w) {
            return new Wildcard(w.upper() == null ? null : substitute(w.upper(), bindings),
                    w.lower() == null ? null : substitute(w.lower(), bindings));
        }
        return t;
    }

    /** Names of type variables still free in t. */
    public static Set<String> freeVars(TypeRef t) {
        Set<String> out = new HashSet<>();
        collect(t, out);
        return out;
    }

    private static void collect(TypeRef t, Set<String> out) {
        if (t instanceof TypeVar v) out.add(v.name());
        else if (t instanceof ClassRef c) c.typeArgs().forEach(a -> collect(a, out));
        else if (t instanceof ArrayRef a) collect(a.component(), out);
        else if (t instanceof Wildcard w) {
            if (w.upper() != null) collect(w.upper(), out);
            if (w.lower() != null) collect(w.lower(), out);
        }
    }

    public static boolean hasFreeVars(TypeRef t) { return !freeVars(t).isEmpty(); }
}
