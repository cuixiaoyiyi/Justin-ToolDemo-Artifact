package cn.ac.ios.justin.oracle;

import cn.ac.ios.justin.core.contract.Constraints;
import cn.ac.ios.justin.core.contract.Constraints.Spec;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.FieldInfo;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Assertion;
import cn.ac.ios.justin.core.test.Outcome.Observed;
import cn.ac.ios.justin.core.test.Statement;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;

/**
 * Semantic oracles from annotations (JNN section 3.3): the annotations on a method describe its
 * return value, the annotations on the fields of the returned object's class describe its state.
 * Both become {@code assertTrue} conditions in the test, and the observed value is checked against
 * them at generation time so a violation can be reported as a suspect defect instead of being
 * silently turned into an "expected" equality assertion.
 */
public final class ReturnContracts {
    private final Function<String, Optional<ClassTarget>> classes;

    public ReturnContracts(Function<String, Optional<ClassTarget>> classes) {
        this.classes = classes == null ? n -> Optional.empty() : classes;
    }

    /** The unified return contract of m (method-level annotations); empty when there is none. */
    public static Spec specOf(MethodTarget m) {
        return Constraints.of(m.annotations());
    }

    /** Assertions over the result variable for the return contract and the returned object's field contracts. */
    public List<Statement> assertions(MethodTarget m, TypeRef resultType, String var) {
        List<Statement> out = new ArrayList<>();
        Spec spec = specOf(m);
        conditions(spec, resultType, var, out, 0);
        return out;
    }

    private void conditions(Spec spec, TypeRef t, String expr, List<Statement> out, int depth) {
        boolean ref = !(t instanceof Primitive);
        String guard = ref ? expr + " != null && " : "";
        if (Boolean.FALSE.equals(spec.nullable) && ref) out.add(assertion(new Assertion.NotNull(expr)));
        if (spec.mustBeNull && ref) out.add(assertion(new Assertion.IsNull(expr)));
        Kind kind = Kind.fromKeyword(SpecialTables.tableFor(t));
        if (kind != null && kind != Kind.BOOLEAN) {
            if (spec.min != null || spec.max != null) {
                List<String> parts = new ArrayList<>();
                if (spec.min != null) parts.add(expr + (spec.minExclusive ? " > " : " >= ") + literal(spec.min, kind));
                if (spec.max != null) parts.add(expr + (spec.maxExclusive ? " < " : " <= ") + literal(spec.max, kind));
                out.add(condition(guard + String.join(" && ", parts), spec.describe()));
            }
            if (spec.minReal != null || spec.maxReal != null) {
                List<String> parts = new ArrayList<>();
                if (spec.minReal != null) parts.add(expr + " >= " + spec.minReal + "D");
                if (spec.maxReal != null) parts.add(expr + " <= " + spec.maxReal + "D");
                out.add(condition(guard + String.join(" && ", parts), spec.describe()));
            }
        }
        if (kind == Kind.BOOLEAN && spec.bool != null) {
            out.add(condition(guard + (spec.bool ? expr : "!" + expr), spec.describe()));
        }
        if (isString(t)) {
            for (String re : spec.patterns) {
                if (!validRegex(re)) continue;
                out.add(condition(guard + "java.util.regex.Pattern.matches(" + javaString(re) + ", " + expr + ")", "@Pattern(" + javaString(re) + ")"));
            }
            if (spec.notBlank) out.add(condition(guard + "!" + expr + ".trim().isEmpty()", "@NotBlank"));
            sizeCondition(spec, expr + ".length()", guard, out);
        } else if (t instanceof ArrayRef) {
            sizeCondition(spec, expr + ".length", guard, out);
        } else if (t instanceof ClassRef c && isCollection(c)) {
            sizeCondition(spec, expr + ".size()", guard, out);
        }
        if (spec.subClass != null && ref) {
            out.add(condition(expr + " instanceof " + spec.subClass.replace('$', '.'), "@SubClass(" + simple(spec.subClass) + ")"));
        }
        // returned object: the class's own field contracts and the method's @Field specs, through public getters
        if (ref && depth < 1 && t instanceof ClassRef c && !isString(t) && !isCollection(c) && kind == null) {
            Optional<ClassTarget> oct = classes.apply(spec.subClass != null ? spec.subClass : c.binaryName());
            if (oct.isEmpty()) return;
            ClassTarget ct = oct.get();
            List<FieldInfo> allFields = allFields(ct);
            List<String> names = new ArrayList<>(spec.fields.keySet());
            for (FieldInfo f : allFields) if (!f.isStatic() && !names.contains(f.name())) names.add(f.name());
            for (String name : names) {
                Spec fs = spec.fields.get(name);
                FieldInfo fi = allFields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
                if (fs == null && fi != null) fs = Constraints.of(fi.annotations());
                if (fs == null || fs.isEmpty()) continue;
                Optional<MethodTarget> getter = getterFor(ct, name);
                if (getter.isEmpty()) continue;
                TypeRef ft = getter.get().returnType();
                List<Statement> nested = new ArrayList<>();
                conditions(fs, ft, expr + "." + getter.get().name() + "()", nested, depth + 1);
                if (!nested.isEmpty() && ref) {
                    // the receiver must exist before its getters are called
                    for (Statement s : nested) {
                        if (s instanceof Statement.Assert a && a.assertion() instanceof Assertion.Condition cond) {
                            out.add(condition(expr + " != null && " + cond.expression(), "field " + name + ": " + cond.message()));
                        } else out.add(s);
                    }
                }
            }
        }
    }

    private static void sizeCondition(Spec spec, String lengthExpr, String guard, List<Statement> out) {
        if (!spec.hasSize()) return;
        List<String> parts = new ArrayList<>();
        if (spec.sizeMin != null) parts.add(lengthExpr + " >= " + spec.sizeMin);
        if (spec.sizeMax != null) parts.add(lengthExpr + " <= " + spec.sizeMax);
        out.add(condition(guard + String.join(" && ", parts), spec.describe()));
    }

    /**
     * Contract lines the observed result violates, judged on the literal value the sandbox saw; empty
     * when it complies or when the value is opaque. Used to flag the test as a suspect defect.
     */
    public List<String> violations(MethodTarget m, TypeRef resultType, Observed observed) {
        List<String> out = new ArrayList<>();
        Spec spec = specOf(m);
        if (spec.isEmpty()) return out;
        boolean ref = !(resultType instanceof Primitive);
        Value v = observed instanceof Observed.Literal l ? l.value() : null;
        if (v instanceof Value.NullValue) {
            if (Boolean.FALSE.equals(spec.nullable)) out.add(firstSource(spec, "@NotNull") + " violated: null returned");
            return out;
        }
        if (spec.mustBeNull && ref && observed != null && !(observed instanceof Observed.Literal l2 && l2.value() instanceof Value.NullValue)) out.add("@Null violated: non-null returned");
        if (v instanceof Value.PrimitiveValue p && p.boxed() instanceof Number n && !(p.boxed() instanceof Boolean)) {
            if (p.kind() == Kind.DOUBLE || p.kind() == Kind.FLOAT) {
                double d = n.doubleValue();
                if (spec.minReal != null && d < spec.minReal || spec.maxReal != null && d > spec.maxReal) out.add(spec.describe() + " violated by " + d);
                if (spec.min != null && d < spec.min || spec.max != null && d > spec.max) out.add(spec.describe() + " violated by " + d);
            } else {
                long x = n.longValue();
                if (spec.min != null && (spec.minExclusive ? x <= spec.min : x < spec.min)) out.add(spec.describe() + " violated by " + x);
                if (spec.max != null && (spec.maxExclusive ? x >= spec.max : x > spec.max)) out.add(spec.describe() + " violated by " + x);
            }
        }
        if (v instanceof Value.PrimitiveValue p && p.boxed() instanceof Character ch) {
            long x = ch;
            if (spec.min != null && x < spec.min || spec.max != null && x > spec.max) out.add(spec.describe() + " violated by '" + ch + "'");
        }
        if (v instanceof Value.PrimitiveValue p && p.boxed() instanceof Boolean b && spec.bool != null && !spec.bool.equals(b)) out.add(spec.describe() + " violated by " + b);
        if (v instanceof Value.StringValue s) {
            for (String re : spec.patterns) if (validRegex(re) && !java.util.regex.Pattern.matches(re, s.value())) out.add("@Pattern(" + javaString(re) + ") violated by " + javaString(abbreviate(s.value())));
            if (spec.notBlank && s.value().trim().isEmpty()) out.add("@NotBlank violated by blank string");
            if (spec.sizeMin != null && s.value().length() < spec.sizeMin || spec.sizeMax != null && s.value().length() > spec.sizeMax) out.add(spec.describe() + " violated by length " + s.value().length());
        }
        if (v instanceof Value.ArrayValue a && spec.hasSize()) {
            int n = a.elements().size();
            if (spec.sizeMin != null && n < spec.sizeMin || spec.sizeMax != null && n > spec.sizeMax) out.add(spec.describe() + " violated by length " + n);
        }
        if (observed instanceof Observed.Collection col && spec.hasSize()) {
            int n = col.elements().size();
            if (spec.sizeMin != null && n < spec.sizeMin || spec.sizeMax != null && n > spec.sizeMax) out.add(spec.describe() + " violated by size " + n);
        }
        return out;
    }

    // ---------- helpers ----------

    private static Statement assertion(Assertion a) { return new Statement.Assert(a); }
    private static Statement condition(String expr, String message) { return new Statement.Assert(new Assertion.Condition(expr, "contract " + message)); }

    private List<FieldInfo> allFields(ClassTarget ct) {
        List<FieldInfo> out = new ArrayList<>();
        ClassTarget cur = ct;
        for (int i = 0; cur != null && i < 6; i++) {
            for (FieldInfo f : cur.fields()) if (out.stream().noneMatch(x -> x.name().equals(f.name()))) out.add(f);
            cur = cur.superClass() == null ? null : classes.apply(cur.superClass().erasureName()).orElse(null);
        }
        return out;
    }

    private Optional<MethodTarget> getterFor(ClassTarget ct, String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        List<MethodTarget> methods = new ArrayList<>();
        ClassTarget cur = ct;
        for (int i = 0; cur != null && i < 6; i++) {
            methods.addAll(cur.methods());
            cur = cur.superClass() == null ? null : classes.apply(cur.superClass().erasureName()).orElse(null);
        }
        return methods.stream()
                .filter(m -> !m.isStatic() && m.isPublic() && !m.isSynthetic() && m.arity() == 0 && !m.returnsVoid())
                .filter(m -> m.name().equals("get" + cap) || m.name().equals("is" + cap) || m.name().equals(field))
                .findFirst();
    }

    private static String literal(long v, Kind k) {
        if (k == Kind.LONG) return v + "L";
        if (k == Kind.DOUBLE) return v + "D";
        if (k == Kind.FLOAT) return v + "F";
        return String.valueOf(v);
    }

    static String javaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static boolean validRegex(String re) {
        try { java.util.regex.Pattern.compile(re); return true; } catch (PatternSyntaxException e) { return false; }
    }

    private static String firstSource(Spec spec, String dflt) {
        for (String s : spec.sources) if (!s.startsWith("@Field") && !s.startsWith("@Element")) return s;
        return dflt;
    }

    private static String abbreviate(String s) { return s.length() > 40 ? s.substring(0, 37) + "..." : s; }
    private static String simple(String binary) { return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1); }
    private static boolean isString(TypeRef t) { return t.erasureName().equals("java.lang.String") || t.erasureName().equals("java.lang.CharSequence"); }
    private static boolean isCollection(ClassRef c) {
        return switch (c.binaryName()) {
            case "java.util.List", "java.util.Collection", "java.util.Set", "java.util.ArrayList", "java.util.HashSet", "java.util.Map", "java.util.HashMap" -> true;
            default -> false;
        };
    }

    /** Numeric table names as the special-value tables use them ("int", "long", ... or the boxed class mapped to them). */
    static final class SpecialTables {
        static String tableFor(TypeRef t) {
            String e = t.erasureName();
            return switch (e) {
                case "java.lang.Integer" -> "int"; case "java.lang.Long" -> "long"; case "java.lang.Short" -> "short"; case "java.lang.Byte" -> "byte";
                case "java.lang.Double" -> "double"; case "java.lang.Float" -> "float"; case "java.lang.Character" -> "char"; case "java.lang.Boolean" -> "boolean";
                default -> e;
            };
        }
    }
}
