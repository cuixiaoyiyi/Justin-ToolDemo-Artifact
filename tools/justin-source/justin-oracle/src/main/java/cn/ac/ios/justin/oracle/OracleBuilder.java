package cn.ac.ios.justin.oracle;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Assertion;
import cn.ac.ios.justin.core.test.Outcome;
import cn.ac.ios.justin.core.test.Outcome.Observed;
import cn.ac.ios.justin.core.test.Statement;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.generics.TypeSubstitution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the statement sequence of a single-call test from an executed tuple: receiver declaration,
 * the invocation, and assertions derived from the observed outcome.
 */
public final class OracleBuilder {

    private static final String RECEIVER = "instance";
    private static final String RESULT = "result";

    private final int javaVersion;
    private final ReturnContracts contracts;
    private final java.util.function.Function<String, java.util.Optional<cn.ac.ios.justin.core.target.ClassTarget>> classLookup;

    /** @param javaVersion language level of the subject; decides which collection factories the assertions may use */
    public OracleBuilder(int javaVersion) { this(javaVersion, null); }

    /** @param classes resolves binary names to scanned classes, so field contracts of returned objects can be asserted through getters */
    public OracleBuilder(int javaVersion, java.util.function.Function<String, java.util.Optional<cn.ac.ios.justin.core.target.ClassTarget>> classes) {
        this.javaVersion = javaVersion;
        this.contracts = new ReturnContracts(classes);
        this.classLookup = classes == null ? n -> java.util.Optional.empty() : classes;
    }

    /**
     * Arguments of a call to an overloaded method (another method of the same name and arity in the
     * class) are cast to the erased parameter types, so javac picks the overload that was executed.
     */
    private List<Value> disambiguate(MethodTarget m, List<Value> args) {
        java.util.Optional<cn.ac.ios.justin.core.target.ClassTarget> ct = classLookup.apply(m.declaringClass());
        if (ct.isEmpty() || ct.get().methods().stream().filter(x -> x.name().equals(m.name()) && x.arity() == m.arity()).count() < 2) return args;
        List<Value> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Value a = args.get(i);
            TypeRef pt = i < m.params().size() ? m.params().get(i).type() : null;
            boolean reference = pt != null && !(pt instanceof TypeRef.Primitive) && !(pt instanceof TypeRef.TypeVar);
            // a String literal too: append(CharSequence, int, int) was executed, append(String, int, int) would be chosen
            boolean castable = a instanceof Value.ObjectValue || a instanceof Value.VarRef || a instanceof Value.ArrayValue || a instanceof Value.StringValue;
            if (reference && castable && !a.type().erasureName().equals(pt.erasureName()) && !(a instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.Boxed)) {
                out.add(new Value.CastValue(erasureRef(pt), a));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * SUSPECT when the observed return value violates the method's annotated contract (JNN's semantic
     * oracle: a returned value outside @Min/@Max, not matching @Pattern, null under @NotNull, ...);
     * empty for exception outcomes, void methods and compliant values.
     */
    public Optional<cn.ac.ios.justin.core.test.Verdict> contractVerdict(InputTuple tuple, Outcome outcome) {
        if (!(outcome instanceof Outcome.Returned r) || r.value() == null || tuple.target().returnsVoid()) return Optional.empty();
        List<String> violated = contracts.violations(tuple.target(), concreteReturn(tuple.target(), tuple), r.value());
        if (violated.isEmpty()) return Optional.empty();
        return Optional.of(cn.ac.ios.justin.core.test.Verdict.suspect("return contract " + String.join("; ", violated)));
    }

    /** Returns empty when the outcome should not become a test (timeout, setup failure, blocked). */
    public Optional<List<Statement>> build(InputTuple tuple, Outcome outcome) {
        MethodTarget m = tuple.target();
        List<Statement> stmts = new ArrayList<>();
        int[] counter = new int[1];
        String recvVar = null;
        if (tuple.receiver() != null) {
            recvVar = RECEIVER;
            declareWithSequence(recvVar, tuple.receiver(), stmts, counter);
        }
        // arguments built by a construction sequence become local variables declared before the call
        List<Value> hoistedArgs = new ArrayList<>();
        for (Value a : tuple.args()) hoistedArgs.add(hoist(a, stmts, counter));
        List<Value> args = disambiguate(m, hoistedArgs);
        TypeRef resultType = concreteReturn(m, tuple);
        if (outcome instanceof Outcome.Returned r) {
            if (m.returnsVoid() || r.value() == null) {
                stmts.add(new Statement.Invoke(null, null, recvVar, m, args));
                return Optional.of(stmts);
            }
            // heap pollution: a method declared to return T (bound to Integer) can hand back a String that came in
            // through an Object-typed parameter; the sandbox saw it via reflection, the test would fail on the cast
            if (r.value() instanceof Observed.Literal lit && !fits(lit.value(), resultType)) resultType = TypeRef.OBJECT;
            stmts.add(new Statement.Invoke(RESULT, resultType, recvVar, m, args));
            if (looksIdentityBased(m, r.value())) {
                // identity hash codes and default toString differ between JVM runs: only assert presence
                stmts.add(new Statement.Assert(new Assertion.NotNull(RESULT)));
            } else {
                stmts.addAll(assertionsFor(r.value(), resultType, javaVersion));
            }
            // semantic oracle: the annotated contract of the return value and of the returned object's fields
            stmts.addAll(contracts.assertions(m, resultType, RESULT));
            return Optional.of(stmts);
        }
        if (outcome instanceof Outcome.Threw t) {
            Statement.Invoke inv = new Statement.Invoke(null, null, recvVar, m, args);
            stmts.add(new Statement.Assert(new Assertion.Throws(exceptionForTarget(t.exceptionClass()), inv)));
            return Optional.of(stmts);
        }
        return Optional.empty();
    }

    /**
     * Sequence values (constructor plus mutator calls) cannot be one expression: declare a variable
     * for the base, emit the mutator calls, and return a reference to the variable. Other values are
     * returned unchanged (after hoisting sequences nested in their arguments).
     */
    private Value hoist(Value v, List<Statement> out, int[] counter) {
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.Sequence) {
            String var = "arg" + counter[0]++;
            declareWithSequence(var, v, out, counter);
            return new Value.VarRef(var, v.type());
        }
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.ConstructorCall cc) {
            List<Value> hoisted = hoistAll(cc.args(), out, counter);
            return hoisted == null ? v : new Value.ObjectValue(o.type(), new Value.Recipe.ConstructorCall(cc.className(), cc.descriptor(), hoisted));
        }
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.StaticCall sc) {
            List<Value> hoisted = hoistAll(sc.args(), out, counter);
            return hoisted == null ? v : new Value.ObjectValue(o.type(), new Value.Recipe.StaticCall(sc.className(), sc.methodName(), sc.descriptor(), hoisted));
        }
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.InstanceCall ic) {
            Value target = hoist(ic.target(), out, counter);
            List<Value> hoisted = hoistAll(ic.args(), out, counter);
            if (target == ic.target() && hoisted == null) return v;
            return new Value.ObjectValue(o.type(), new Value.Recipe.InstanceCall(target, ic.declaringClass(), ic.methodName(), ic.descriptor(), hoisted == null ? ic.args() : hoisted));
        }
        if (v instanceof Value.ArrayValue a) {
            List<Value> hoisted = hoistAll(a.elements(), out, counter);
            return hoisted == null ? v : new Value.ArrayValue(a.component(), hoisted);
        }
        if (v instanceof Value.CastValue c) {
            Value inner = hoist(c.inner(), out, counter);
            return inner == c.inner() ? v : new Value.CastValue(c.to(), inner);
        }
        if (v instanceof Value.FunctionValue f && f.constant() != null) {
            // a captured local must be effectively final: a hoisted variable is
            Value c = hoist(f.constant(), out, counter);
            return c == f.constant() ? v : new Value.FunctionValue(f.iface(), f.methodName(), f.arity(), f.behavior(), c);
        }
        return v;
    }

    /** Returns null when nothing changed. */
    private List<Value> hoistAll(List<Value> values, List<Statement> out, int[] counter) {
        List<Value> res = new ArrayList<>(values.size());
        boolean changed = false;
        for (Value x : values) {
            Value h = hoist(x, out, counter);
            if (h != x) changed = true;
            res.add(h);
        }
        return changed ? res : null;
    }

    private void declareWithSequence(String var, Value v, List<Statement> out, int[] counter) {
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.Sequence seq) {
            Value base = hoist(new Value.ObjectValue(o.type(), seq.base()), out, counter);
            out.add(new Statement.Declare(var, v.type(), base));
            for (Value.Call call : seq.mutators()) {
                List<Value> args = new ArrayList<>();
                for (Value a : call.args()) args.add(hoist(a, out, counter));
                out.add(new Statement.Invoke(null, null, var, mutatorTarget(call), args));
            }
        } else {
            out.add(new Statement.Declare(var, v.type(), hoist(v, out, counter)));
        }
    }

    /** A MethodTarget just rich enough for the emitter to render obj.name(args). */
    private static MethodTarget mutatorTarget(Value.Call call) {
        var sig = cn.ac.ios.justin.generics.SignatureParser.parseMethodDescriptor(call.descriptor());
        List<cn.ac.ios.justin.core.target.Parameter> ps = new ArrayList<>();
        for (int i = 0; i < sig.params().size(); i++) ps.add(new cn.ac.ios.justin.core.target.Parameter(i, "p" + i, sig.params().get(i), List.of()));
        return new MethodTarget(call.declaringClass(), call.methodName(), call.descriptor(), java.lang.reflect.Modifier.PUBLIC,
                List.of(), ps, sig.returnType(), List.of(), List.of(), -1);
    }

    /** "pkg.Cls@1a2b3c4d" anywhere in the string, or a bare identity hash on its own. */
    private static final java.util.regex.Pattern IDENTITY_STRING = java.util.regex.Pattern.compile("[\\w$\\]]@[0-9a-f]{4,8}(?![0-9a-zA-Z])|^[0-9a-f]{6,8}$");

    /**
     * A string that equals a system property or environment variable of the generating JVM (class path,
     * user.dir, java.home, PATH, ...) describes this machine, not the subject: asserted non-null only.
     */
    static boolean isEnvironmentValue(String v) {
        if (v.length() < 3) return false;
        try {
            for (Object o : System.getProperties().values()) if (v.equals(o)) return true;
            return System.getenv().containsValue(v);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Whether a literal result can be assigned to a variable of the declared (concrete) result type. */
    static boolean fits(Value v, TypeRef declared) {
        String d = declared.erasureName();
        if (v instanceof Value.NullValue || d.equals("java.lang.Object") || d.equals("java.io.Serializable") || d.equals("java.lang.Comparable")) return true;
        if (v instanceof Value.StringValue) return d.equals("java.lang.String") || d.equals("java.lang.CharSequence");
        if (v instanceof Value.PrimitiveValue p) {
            if (declared instanceof TypeRef.Primitive) return true; // numeric widening is javac's business; the declared primitive stands
            return d.equals(p.kind().boxed) || (d.equals("java.lang.Number") && p.kind() != TypeRef.Kind.BOOLEAN && p.kind() != TypeRef.Kind.CHAR);
        }
        return true;
    }

    /** Default Object.toString()/identityHashCode output, or a method whose name says it is identity based. */
    static boolean looksIdentityBased(MethodTarget m, Observed obs) {
        String n = m.name().toLowerCase();
        if (n.contains("identity") || n.equals("hashcode")) return !(obs instanceof Observed.Literal l && l.value() instanceof Value.NullValue);
        // hash(...) helpers of identity-based containers (ReferenceIdentityMap, IdentityMap) return identity hashes
        if (n.startsWith("hash") && m.declaringClass().toLowerCase().contains("identity")) return !(obs instanceof Observed.Literal l && l.value() instanceof Value.NullValue);
        if (obs instanceof Observed.Literal l && l.value() instanceof Value.StringValue s) {
            // the sandbox stands in for a synthesized lambda with a proxy whose toString is fixed; the lambda in the
            // emitted test prints as its synthetic class name
            return IDENTITY_STRING.matcher(s.value()).find() || s.value().contains("justin-lambda") || isEnvironmentValue(s.value());
        }
        // wall-clock readings (epoch millis / nanoTime) are stable within a millisecond, so the repetition
        // check misses them; they never reproduce in another JVM
        if (obs instanceof Observed.Literal l && l.value() instanceof Value.PrimitiveValue p && p.kind() == TypeRef.Kind.LONG) {
            long v = (Long) p.boxed();
            boolean epochMillis = v > 1_000_000_000_000L && v < 4_000_000_000_000L;
            boolean nanoScale = v > 1_000_000_000_000_000L;
            if (epochMillis || nanoScale) return true;
            if (n.contains("time") || n.contains("clock") || n.contains("nano") || n.contains("millis")) return true;
        }
        return false;
    }

    /** JDK exception classes that do not exist at Java 8 API level, mapped to their nearest Java 8 supertype. */
    private static final java.util.Map<String, String> POST_JAVA8_EXCEPTIONS = java.util.Map.of(
            "java.lang.reflect.InaccessibleObjectException", "java.lang.RuntimeException",
            "java.lang.invoke.StringConcatException", "java.lang.Exception",
            "java.util.concurrent.CompletionException", "java.lang.RuntimeException");

    private String exceptionForTarget(String exceptionClass) {
        if (javaVersion < 9) return POST_JAVA8_EXCEPTIONS.getOrDefault(exceptionClass, exceptionClass);
        return exceptionClass;
    }

    private static TypeRef concreteReturn(MethodTarget m, InputTuple tuple) {
        if (m.isConstructor()) return TypeRef.ClassRef.of(m.declaringClass());
        // a return type mentioning a method-level type variable is inferred from the arguments by javac,
        // and that inference need not agree with our binding: declare the result as Object
        for (TypeRef.TypeVar v : m.typeParams()) {
            if (TypeSubstitution.freeVars(m.returnType()).contains(v.name())) return TypeRef.OBJECT;
        }
        TypeRef t = TypeSubstitution.substitute(m.returnType(), tuple.typeBindings());
        // leftover variables or wildcards cannot be declared; fall back to the erasure
        if (TypeSubstitution.hasFreeVars(t) || t.toSource().contains("?")) return erasureRef(t);
        return t;
    }

    /** List.of / Set.of on Java 9+; Arrays.asList and HashSet on Java 8. */
    static Value expectedCollection(String factory, List<Value> elems, int javaVersion) {
        if (javaVersion >= 9) {
            return new Value.ObjectValue(TypeRef.ClassRef.of(factory),
                    new Value.Recipe.StaticCall(factory, "of", "([Ljava/lang/Object;)L" + factory.replace('.', '/') + ";", elems));
        }
        Value asList = new Value.ObjectValue(TypeRef.ClassRef.of("java.util.List"),
                new Value.Recipe.StaticCall("java.util.Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", elems));
        if (factory.equals("java.util.Set")) {
            return new Value.ObjectValue(TypeRef.ClassRef.of("java.util.HashSet"),
                    new Value.Recipe.ConstructorCall("java.util.HashSet", "(Ljava/util/Collection;)V", List.of(asList)));
        }
        return asList;
    }

    private static TypeRef erasureRef(TypeRef t) {
        String e = t.erasureName();
        int dims = 0;
        while (e.endsWith("[]")) { dims++; e = e.substring(0, e.length() - 2); }
        TypeRef base = TypeRef.Kind.fromKeyword(e) != null ? new TypeRef.Primitive(TypeRef.Kind.fromKeyword(e)) : TypeRef.ClassRef.of(e);
        for (int i = 0; i < dims; i++) base = new TypeRef.ArrayRef(base);
        return base;
    }

    private static List<Statement> assertionsFor(Observed obs, TypeRef resultType, int javaVersion) {
        List<Statement> out = new ArrayList<>();
        if (obs instanceof Observed.Literal lit) {
            Value v = lit.value();
            if (v instanceof Value.NullValue) out.add(new Statement.Assert(new Assertion.IsNull(RESULT)));
            else if (v instanceof Value.ArrayValue) {
                // a result declared as Object must be cast back to an array type for assertArrayEquals
                TypeRef cast = resultType.equals(TypeRef.OBJECT) ? v.type() : null;
                out.add(new Statement.Assert(new Assertion.ArrayEquals(v, RESULT, cast)));
            }
            else if (v instanceof Value.PrimitiveValue p && p.kind() == TypeRef.Kind.BOOLEAN)
                out.add(new Statement.Assert((Boolean) p.boxed() ? new Assertion.IsTrue(RESULT) : new Assertion.IsFalse(RESULT)));
            else if (v instanceof Value.PrimitiveValue p && (p.kind() == TypeRef.Kind.DOUBLE || p.kind() == TypeRef.Kind.FLOAT)) {
                double d = ((Number) p.boxed()).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) out.add(new Statement.Assert(new Assertion.Equals(v, RESULT, null)));
                else out.add(new Statement.Assert(new Assertion.Equals(v, RESULT, Math.max(1e-9, Math.abs(d) * 1e-9))));
            } else out.add(new Statement.Assert(new Assertion.Equals(v, RESULT, null)));
        } else if (obs instanceof Observed.BigString big) {
            out.add(new Statement.Assert(new Assertion.Equals(Value.PrimitiveValue.of(big.length()), RESULT + ".length()", null)));
            if (!big.head().isEmpty()) {
                out.add(new Statement.Assert(new Assertion.Equals(new Value.StringValue(big.head()), RESULT + ".substring(0, " + big.head().length() + ")", null)));
            }
        } else if (obs instanceof Observed.Collection col) {
            List<Value> elems = new ArrayList<>();
            boolean literal = true;
            for (Observed e : col.elements()) {
                if (e instanceof Observed.Literal l && !(l.value() instanceof Value.NullValue)) elems.add(l.value());
                else { literal = false; break; }
            }
            if (literal) {
                String factory = col.ordered() ? "java.util.List" : "java.util.Set";
                Value expected = expectedCollection(factory, elems, javaVersion);
                out.add(new Statement.Assert(new Assertion.Equals(expected, RESULT, null)));
            } else {
                out.add(new Statement.Assert(new Assertion.NotNull(RESULT)));
            }
        } else {
            out.add(new Statement.Assert(new Assertion.NotNull(RESULT)));
        }
        return out;
    }
}
