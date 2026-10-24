package cn.ac.ios.justin.symexec.strings;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a sliced value is in terms of the method's parameters: the trajectory of a SAIS folded into
 * one expression. Terms over side-effect-free JDK classes can be evaluated on concrete parameter
 * values, which is how candidate inputs are checked against the predicates of a path.
 */
public sealed interface Term permits Term.Const, Term.Param, Term.Call, Term.Concat, Term.Elem, Term.Len, Term.Bin, Term.Unknown {

    /** A constant: String, Integer (also chars and booleans, as Jimple has them), Long, or null. */
    record Const(Object value) implements Term {}
    record Param(int index, String type) implements Term {}
    /** base is null for static calls. */
    record Call(String owner, String name, List<String> paramTypes, Term base, List<Term> args, String returnType) implements Term {}
    /** The content of a builder, or a compiled string concatenation. */
    record Concat(List<Term> parts) implements Term {}
    record Elem(Term array, Term index) implements Term {}
    record Len(Term array) implements Term {}
    /** Integer + and -. */
    record Bin(String op, Term left, Term right) implements Term {}
    record Unknown(String why) implements Term {}

    /** Evaluation had no defined result: an unknown subterm, a parameter without a value, a class outside the whitelist. */
    final class NotEvaluable extends RuntimeException {
        public NotEvaluable(String why) { super(why, null, false, false); }
    }

    /** The evaluated code threw: the path would leave through an exception here. */
    final class Threw extends RuntimeException {
        public Threw(Throwable cause) { super(String.valueOf(cause), null, false, false); }
    }

    Set<String> BUILDERS = Set.of("java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder");
    Set<String> STRINGLIKE = Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder");
    Set<String> EVALUABLE = Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.AbstractStringBuilder",
            "java.lang.Character", "java.lang.Integer", "java.lang.Long", "java.lang.Boolean", "java.lang.Math", "java.util.Objects", "java.lang.Object", "java.util.regex.Pattern");

    static boolean isStringLike(String type) { return STRINGLIKE.contains(type); }

    /** Parameter indices the term mentions. */
    static void params(Term t, Set<Integer> out) {
        if (t instanceof Param p) out.add(p.index());
        else if (t instanceof Call c) { if (c.base() != null) params(c.base(), out); for (Term a : c.args()) params(a, out); }
        else if (t instanceof Concat c) for (Term a : c.parts()) params(a, out);
        else if (t instanceof Elem e) { params(e.array(), out); params(e.index(), out); }
        else if (t instanceof Len l) params(l.array(), out);
        else if (t instanceof Bin b) { params(b.left(), out); params(b.right(), out); }
    }

    /** The term with f applied to its immediate subterms. */
    static Term map(Term t, java.util.function.UnaryOperator<Term> f) {
        if (t instanceof Call c) {
            List<Term> args = new ArrayList<>();
            for (Term a : c.args()) args.add(f.apply(a));
            return new Call(c.owner(), c.name(), c.paramTypes(), c.base() == null ? null : f.apply(c.base()), args, c.returnType());
        }
        if (t instanceof Concat cat) {
            List<Term> parts = new ArrayList<>();
            for (Term a : cat.parts()) parts.add(f.apply(a));
            return new Concat(parts);
        }
        if (t instanceof Elem e) return new Elem(f.apply(e.array()), f.apply(e.index()));
        if (t instanceof Len l) return new Len(f.apply(l.array()));
        if (t instanceof Bin b) return new Bin(b.op(), f.apply(b.left()), f.apply(b.right()));
        return t;
    }

    /** Every occurrence of target in t replaced. */
    static Term replace(Term t, Term target, Term by) {
        if (t.equals(target)) return by;
        return map(t, x -> replace(x, target, by));
    }

    /** Visits t and all its subterms, outermost first. */
    static void visit(Term t, java.util.function.Consumer<Term> f) {
        if (t == null) return;
        f.accept(t);
        map(t, x -> { visit(x, f); return x; });
    }

    static boolean hasUnknown(Term t) {
        if (t instanceof Unknown) return true;
        if (t instanceof Call c) { if (c.base() != null && hasUnknown(c.base())) return true; for (Term a : c.args()) if (hasUnknown(a)) return true; return false; }
        if (t instanceof Concat c) { for (Term a : c.parts()) if (hasUnknown(a)) return true; return false; }
        if (t instanceof Elem e) return hasUnknown(e.array()) || hasUnknown(e.index());
        if (t instanceof Len l) return hasUnknown(l.array());
        if (t instanceof Bin b) return hasUnknown(b.left()) || hasUnknown(b.right());
        return false;
    }

    /**
     * The value of the term under the parameter values in env (String for every string-like parameter,
     * Integer for int, char and boolean ones; a key mapped to null is the null reference).
     */
    static Object eval(Term t, Map<Integer, Object> env) {
        if (t instanceof Const c) return c.value();
        if (t instanceof Param p) {
            if (!env.containsKey(p.index())) throw new NotEvaluable("parameter " + p.index() + " has no value");
            return env.get(p.index());
        }
        if (t instanceof Unknown u) throw new NotEvaluable(u.why());
        if (t instanceof Concat c) {
            StringBuilder sb = new StringBuilder();
            for (Term part : c.parts()) sb.append(eval(part, env));
            return sb.toString();
        }
        if (t instanceof Bin b) {
            long l = number(eval(b.left(), env)), r = number(eval(b.right(), env));
            return (int) (b.op().equals("+") ? l + r : l - r);
        }
        if (t instanceof Len l) {
            Object a = eval(l.array(), env);
            if (a == null) throw new Threw(new NullPointerException());
            if (!a.getClass().isArray()) throw new NotEvaluable("length of a non-array");
            return java.lang.reflect.Array.getLength(a);
        }
        if (t instanceof Elem e) {
            Object a = eval(e.array(), env);
            Object i = eval(e.index(), env);
            if (a == null) throw new Threw(new NullPointerException());
            if (!a.getClass().isArray()) throw new NotEvaluable("element of a non-array");
            try {
                return java.lang.reflect.Array.get(a, (int) number(i));
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw new Threw(ex);
            }
        }
        return call((Call) t, env);
    }

    private static Object call(Call c, Map<Integer, Object> env) {
        if (!EVALUABLE.contains(c.owner())) throw new NotEvaluable("call into " + c.owner());
        Object base = c.base() == null ? null : eval(c.base(), env);
        Object[] args = new Object[c.args().size()];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = classOf(c.paramTypes().get(i));
            args[i] = convert(eval(c.args().get(i), env), types[i]);
        }
        try {
            Class<?> owner = Class.forName(c.owner());
            if (c.name().equals("<init>")) throw new NotEvaluable("constructor");
            Method m = owner.getMethod(c.name(), types);
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            if (!isStatic) {
                if (base == null) throw new Threw(new NullPointerException());
                // builders are represented by their content
                if (BUILDERS.contains(c.owner()) && base instanceof String s) base = new StringBuilder(s);
                if (!owner.isInstance(base)) throw new NotEvaluable("receiver is not a " + c.owner());
            }
            Object r = m.invoke(isStatic ? null : base, args);
            return r instanceof StringBuilder || r instanceof StringBuffer ? r.toString() : r;
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new Threw(ex.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException ex) {
            throw new NotEvaluable(ex.toString());
        }
    }

    private static Class<?> classOf(String jimpleType) {
        switch (jimpleType) {
            case "int": return int.class; case "char": return char.class; case "boolean": return boolean.class; case "long": return long.class;
            case "byte": return byte.class; case "short": return short.class; case "double": return double.class; case "float": return float.class;
            default:
                if (jimpleType.endsWith("[]")) throw new NotEvaluable("array argument");
                try {
                    return Class.forName(jimpleType);
                } catch (ClassNotFoundException e) {
                    throw new NotEvaluable("unknown class " + jimpleType);
                }
        }
    }

    private static Object convert(Object v, Class<?> to) {
        if (to == char.class) return (char) number(v);
        if (to == int.class) return (int) number(v);
        if (to == long.class) return number(v);
        if (to == boolean.class) return v instanceof Boolean b ? b : number(v) != 0;
        if (to == short.class) return (short) number(v);
        if (to == byte.class) return (byte) number(v);
        if (to.isPrimitive()) throw new NotEvaluable("floating-point argument");
        if (v != null && !to.isInstance(v)) {
            if (StringBuilder.class == to && v instanceof String s) return new StringBuilder(s);
            if (StringBuffer.class == to && v instanceof String s) return new StringBuffer(s);
            throw new NotEvaluable("argument is not a " + to.getName());
        }
        return v;
    }

    /** Integers, chars and booleans on Jimple's common integer scale. */
    static long number(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        if (v instanceof Character c) return c;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Short s) return s;
        if (v instanceof Byte b) return b;
        throw new NotEvaluable("not an integer: " + v);
    }

    /** A path predicate: {@code left op right} holds when {@code holds}, fails otherwise. */
    record Pred(Term left, String op, Term right, boolean holds, int position) {

        /** The relation that is true on the path. */
        public String effectiveOp() { return holds ? op : negate(op); }

        public Set<Integer> params() {
            Set<Integer> out = new java.util.TreeSet<>();
            Term.params(left, out);
            Term.params(right, out);
            return out;
        }

        /** TRUE / FALSE when both sides evaluate; null when the predicate says nothing under env. A throwing side fails the predicate. */
        public Boolean check(Map<Integer, Object> env) {
            Object l, r;
            try {
                l = eval(left, env);
                r = eval(right, env);
            } catch (NotEvaluable e) {
                return null;
            } catch (Threw e) {
                return Boolean.FALSE;
            }
            return compare(l, effectiveOp(), r);
        }

        static Boolean compare(Object l, String op, Object r) {
            boolean refs = l == null || r == null || !(isNumber(l) && isNumber(r));
            if (refs) {
                if (l != null && r != null) return null;    // identity of two non-null references is not something values decide
                boolean same = l == null && r == null;
                return op.equals("==") ? same : op.equals("!=") ? !same : null;
            }
            long a = number(l), b = number(r);
            switch (op) {
                case "==": return a == b; case "!=": return a != b; case "<": return a < b;
                case "<=": return a <= b; case ">": return a > b; case ">=": return a >= b;
                default: return null;
            }
        }

        private static boolean isNumber(Object o) { return o instanceof Number || o instanceof Character || o instanceof Boolean; }

        public static String negate(String op) {
            switch (op) {
                case "==": return "!="; case "!=": return "=="; case "<": return ">="; case ">=": return "<";
                case ">": return "<="; case "<=": return ">"; default: return op;
            }
        }

        public static String flip(String op) {
            switch (op) {
                case "<": return ">"; case ">": return "<"; case "<=": return ">="; case ">=": return "<="; default: return op;
            }
        }
    }

    static List<Term> list(Term... ts) { return new ArrayList<>(List.of(ts)); }
}
