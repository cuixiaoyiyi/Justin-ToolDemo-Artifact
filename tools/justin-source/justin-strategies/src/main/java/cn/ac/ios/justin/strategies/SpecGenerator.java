package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.FieldInfo;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;
import cn.ac.ios.justin.core.contract.Constraints;
import cn.ac.ios.justin.core.contract.Constraints.Spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

/**
 * Turns a {@link Spec} into candidate values, partitioned the JNN way: for a numeric domain
 * [min, max] the bounds themselves, one step outside them (illegal), values inside, and values far
 * outside; strings sampled from and outside a regular expression; arrays and lists with constrained
 * elements; objects built by the value generator and then adjusted so their fields satisfy the nested
 * specs (setter first, constructor parameter of the same name second); temporal values before or after
 * now. Every candidate carries a note naming the annotation it comes from or violates.
 */
final class SpecGenerator {
    private static final int MAX_DEPTH = 3;

    private final GenerationContext ctx;
    private final ValueGenerator gen;
    private final Random rnd;
    private final String strategy;

    SpecGenerator(GenerationContext ctx, Random rnd, String strategy) {
        this.ctx = ctx;
        this.gen = new ValueGenerator(ctx);
        this.rnd = rnd;
        this.strategy = strategy;
    }

    List<Candidate> candidates(Spec spec, TypeRef t) { return candidates(spec, t, 0); }

    List<Candidate> candidates(Spec spec, TypeRef t, int depth) {
        List<Candidate> out = new ArrayList<>();
        boolean ref = !(t instanceof Primitive);
        String table = SpecialValues.tableFor(t);
        Kind kind = Kind.fromKeyword(table);

        if (spec.mustBeNull) {
            if (ref) { legal(out, new NullValue(t), "@Null"); nonNull(t).ifPresent(v -> illegal(out, v, "violates @Null")); }
            return out;
        }
        if (ref && spec.nullable != null) {
            if (spec.nullable) legal(out, new NullValue(t), "nullable");
            else illegal(out, new NullValue(t), "violates " + firstSource(spec, "@NotNull"));
        }
        if (kind != null && kind != Kind.BOOLEAN && kind != Kind.VOID) numeric(spec, t, kind, out);
        if (isBoolean(t) && spec.bool != null) {
            legal(out, bool(t, spec.bool), firstSource(spec, "@AssertTrue"));
            illegal(out, bool(t, !spec.bool), "violates " + firstSource(spec, "@AssertTrue"));
        }
        if (isString(t)) string(spec, t, out);
        if (t instanceof ArrayRef a) array(spec, a, out, depth);
        if (t instanceof ClassRef c && isCollection(c)) collection(spec, c, out, depth);
        if (t instanceof ClassRef c && isTemporal(c) && spec.temporal != null) temporal(spec, c, out);
        if (t instanceof ClassRef c && !isString(t) && !isCollection(c) && !isTemporal(c) && kind == null && (spec.subClass != null || !spec.fields.isEmpty() || hasFieldContracts(c))) {
            object(spec, c, out, depth);
        }
        for (Object o : spec.examples) parse(table, o, out, true, "@Examples");
        for (Object o : spec.invalid) parse(table, o, out, false, "declared invalid by @Examples");
        return out;
    }

    // ---------- numbers ----------

    private void numeric(Spec spec, TypeRef t, Kind kind, List<Candidate> out) {
        boolean real = kind == Kind.DOUBLE || kind == Kind.FLOAT;
        if (real && (spec.minReal != null || spec.maxReal != null)) {
            Double lo = spec.minReal, hi = spec.maxReal;
            if (lo != null) { legal(out, realValue(t, lo), firstSource(spec, "@DecimalMin") + " bound"); illegal(out, realValue(t, lo - 1), "violates " + firstSource(spec, "@DecimalMin")); }
            if (hi != null) { legal(out, realValue(t, hi), firstSource(spec, "@DecimalMax") + " bound"); illegal(out, realValue(t, hi + 1), "violates " + firstSource(spec, "@DecimalMax")); }
            if (lo != null && hi != null && hi > lo) legal(out, realValue(t, lo + (hi - lo) / 2), "inside " + spec.describe());
        }
        if (spec.min == null && spec.max == null) return;
        Long lo = spec.min == null ? null : spec.min + (spec.minExclusive ? 1 : 0);
        Long hi = spec.max == null ? null : spec.max - (spec.maxExclusive ? 1 : 0);
        String src = spec.describe();
        // bounds and one step outside
        if (lo != null) { num(t, lo, out, true, src + " lower bound"); num(t, lo - 1, out, false, "violates " + src + " (below)"); }
        if (hi != null) { num(t, hi, out, true, src + " upper bound"); num(t, hi + 1, out, false, "violates " + src + " (above)"); }
        // inside the interval
        TreeSet<Long> inside = new TreeSet<>();
        if (lo != null && hi != null) {
            if (hi > lo) { inside.add(lo + (hi - lo) / 2); inside.add(lo + 1); inside.add(hi - 1); }
            if (hi - lo > 4) inside.add(lo + 1 + Math.floorMod(rnd.nextLong(), hi - lo - 1));
        } else if (lo != null) { inside.add(lo + 1); inside.add(lo + 10); inside.add(lo + 100); }
        else { inside.add(hi - 1); inside.add(hi - 10); inside.add(hi - 100); }
        for (long v : inside) if ((lo == null || v >= lo) && (hi == null || v <= hi)) num(t, v, out, true, "inside " + src);
        // far outside
        if (lo != null) { num(t, lo - 100, out, false, "violates " + src + " (far below)"); num(t, Long.MIN_VALUE, out, false, "violates " + src + " (type minimum)"); }
        if (hi != null) { num(t, hi + 100, out, false, "violates " + src + " (far above)"); num(t, Long.MAX_VALUE, out, false, "violates " + src + " (type maximum)"); }
    }

    private void num(TypeRef t, long v, List<Candidate> out, boolean legal, String note) {
        AnnotationStrategy.numeric(t, v).ifPresent(x -> add(out, x, legal, note));
    }

    private static Value realValue(TypeRef t, double d) {
        PrimitiveValue p = t.erasureName().equals("float") || t.erasureName().equals("java.lang.Float") ? PrimitiveValue.of((float) d) : PrimitiveValue.of(d);
        return t instanceof Primitive ? p : new ObjectValue(ClassRef.of(p.kind().boxed), new Recipe.Boxed(p));
    }

    // ---------- strings ----------

    private void string(Spec spec, TypeRef t, List<Candidate> out) {
        for (String re : spec.patterns) {
            if (!RegexStrings.valid(re)) continue;
            List<String> ok = RegexStrings.matching(re, 3, rnd);
            for (String s : ok) if (fitsSize(spec, s.length())) legal(out, new StringValue(s), "matches @Pattern(\"" + abbreviate(re) + "\")");
            for (String s : RegexStrings.notMatching(re, 2, rnd)) illegal(out, new StringValue(s), "violates @Pattern(\"" + abbreviate(re) + "\")");
        }
        if (spec.patterns.isEmpty() && spec.hasSize()) {
            int min = spec.sizeMin == null ? 0 : spec.sizeMin, max = spec.sizeMax == null ? Integer.MAX_VALUE : spec.sizeMax;
            legal(out, new StringValue(letters(min)), spec.describe() + " lower bound");
            if (max != Integer.MAX_VALUE && max < 10_000) legal(out, new StringValue(letters(max)), spec.describe() + " upper bound");
            if (max != Integer.MAX_VALUE && max > min + 1) legal(out, new StringValue(letters(min + (max - min) / 2)), "inside " + spec.describe());
            if (min > 0) illegal(out, new StringValue(letters(min - 1)), "violates " + spec.describe() + " (too short)");
            if (max != Integer.MAX_VALUE && max < 10_000) illegal(out, new StringValue(letters(max + 1)), "violates " + spec.describe() + " (too long)");
        } else if (!spec.patterns.isEmpty() && spec.sizeMax != null && spec.sizeMax < 10_000) {
            illegal(out, new StringValue(letters(spec.sizeMax + 1)), "violates " + spec.describe() + " (too long)");
        }
        if (spec.notBlank) {
            legal(out, new StringValue("x"), "@NotBlank");
            illegal(out, new StringValue(""), "violates @NotBlank");
            illegal(out, new StringValue("   "), "violates @NotBlank");
        }
    }

    private static boolean fitsSize(Spec spec, int len) {
        return (spec.sizeMin == null || len >= spec.sizeMin) && (spec.sizeMax == null || len <= spec.sizeMax);
    }

    private static String letters(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append((char) ('a' + i % 26));
        return sb.toString();
    }

    private static String abbreviate(String re) { return re.length() > 30 ? re.substring(0, 27) + "..." : re; }

    // ---------- arrays and collections ----------

    private void array(Spec spec, ArrayRef a, List<Candidate> out, int depth) {
        TypeRef comp = ctx.concreteType(a.component());
        if (spec.hasSize()) {
            int min = spec.sizeMin == null ? 0 : spec.sizeMin, max = spec.sizeMax == null ? Integer.MAX_VALUE : spec.sizeMax;
            legal(out, arrayOf(comp, min, spec, depth, true), spec.describe() + " lower bound");
            if (max != Integer.MAX_VALUE && max < 64) legal(out, arrayOf(comp, max, spec, depth, true), spec.describe() + " upper bound");
            if (min > 0) illegal(out, arrayOf(comp, min - 1, spec, depth, true), "violates " + spec.describe() + " (too short)");
            if (max != Integer.MAX_VALUE && max < 64) illegal(out, arrayOf(comp, max + 1, spec, depth, true), "violates " + spec.describe() + " (too long)");
        }
        if (!spec.elements.isEmpty() && depth < MAX_DEPTH) {
            int n = Math.max(spec.elements.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1, spec.sizeMin == null ? 2 : spec.sizeMin);
            legal(out, arrayOf(comp, n, spec, depth, true), "elements satisfy " + spec.describe());
            for (var e : spec.elements.entrySet()) {
                Optional<Value> bad = firstCandidate(e.getValue(), comp, depth + 1, false);
                if (bad.isEmpty()) continue;
                ArrayValue base = arrayOf(comp, n, spec, depth, true);
                List<Value> elems = new ArrayList<>(base.elements());
                elems.set(e.getKey(), bad.get());
                illegal(out, new ArrayValue(comp, elems), "element " + e.getKey() + " violates " + e.getValue().describe());
            }
        }
    }

    private ArrayValue arrayOf(TypeRef comp, int n, Spec spec, int depth, boolean legal) {
        List<Value> elems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Spec es = spec.elements.get(i);
            Value v = null;
            if (es != null) v = firstCandidate(es, comp, depth + 1, legal).orElse(null);
            if (v == null) v = gen.generate(comp).orElse(new NullValue(comp));
            elems.add(v);
        }
        return new ArrayValue(comp, elems);
    }

    private void collection(Spec spec, ClassRef c, List<Candidate> out, int depth) {
        TypeRef elem = c.typeArgs().isEmpty() ? TypeRef.OBJECT : ctx.concreteType(c.typeArgs().get(0));
        boolean set = c.binaryName().equals("java.util.Set") || c.binaryName().equals("java.util.HashSet");
        if (spec.hasSize()) {
            int min = spec.sizeMin == null ? 0 : spec.sizeMin, max = spec.sizeMax == null ? Integer.MAX_VALUE : spec.sizeMax;
            legal(out, listValue(elem, min, spec, depth, set), spec.describe() + " lower bound");
            if (max != Integer.MAX_VALUE && max < 64) legal(out, listValue(elem, max, spec, depth, set), spec.describe() + " upper bound");
            if (min > 0) illegal(out, listValue(elem, min - 1, spec, depth, set), "violates " + spec.describe() + " (too small)");
            if (max != Integer.MAX_VALUE && max < 64) illegal(out, listValue(elem, max + 1, spec, depth, set), "violates " + spec.describe() + " (too large)");
        }
        if (!spec.elements.isEmpty() && !set && depth < MAX_DEPTH) {
            int n = Math.max(spec.elements.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1, spec.sizeMin == null ? 2 : spec.sizeMin);
            legal(out, listValue(elem, n, spec, depth, false), "elements satisfy " + spec.describe());
        }
    }

    private Value listValue(TypeRef elem, int n, Spec spec, int depth, boolean set) {
        List<Value> elems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Spec es = spec.elements.get(i);
            Value v = es == null ? null : firstCandidate(es, elem, depth + 1, true).orElse(null);
            if (v == null) v = set ? distinct(elem, i) : gen.generate(elem).orElse(new NullValue(elem));
            elems.add(v);
        }
        String impl = set ? "java.util.HashSet" : "java.util.ArrayList";
        String iface = set ? "java.util.Set" : "java.util.List";
        if (elems.isEmpty()) return new ObjectValue(new ClassRef(impl, List.of(elem)), new Recipe.ConstructorCall(impl, "()V", List.of()));
        Value inner = ValueGenerator.listOf(elem, elems, iface, ctx.config().javaVersion());
        return new ObjectValue(new ClassRef(impl, List.of(elem)), new Recipe.ConstructorCall(impl, "(Ljava/util/Collection;)V", List.of(inner)));
    }

    private Value distinct(TypeRef elem, int i) {
        if (elem.erasureName().equals("java.lang.String")) return new StringValue("s" + i);
        return AnnotationStrategy.numeric(elem, i).orElseGet(() -> gen.generate(elem).orElse(new NullValue(elem)));
    }

    // ---------- temporal ----------

    private void temporal(Spec spec, ClassRef c, List<Candidate> out) {
        boolean past = spec.temporal.startsWith("past");
        Value before = dateValue(c, false), after = dateValue(c, true);
        if (before == null || after == null) return;
        legal(out, past ? before : after, "@" + spec.temporal);
        illegal(out, past ? after : before, "violates @" + spec.temporal);
    }

    /** A value of the date type clearly in the past (2001) or the future (2099). */
    private static Value dateValue(ClassRef c, boolean future) {
        int year = future ? 2099 : 2001;
        long epochMillis = future ? 4_070_908_800_000L : 978_307_200_000L;
        switch (c.binaryName()) {
            case "java.time.LocalDate": return new ObjectValue(c, new Recipe.StaticCall("java.time.LocalDate", "of", "(III)Ljava/time/LocalDate;", List.of(PrimitiveValue.of(year), PrimitiveValue.of(1), PrimitiveValue.of(1))));
            case "java.time.LocalDateTime": return new ObjectValue(c, new Recipe.StaticCall("java.time.LocalDateTime", "of", "(IIIII)Ljava/time/LocalDateTime;", List.of(PrimitiveValue.of(year), PrimitiveValue.of(1), PrimitiveValue.of(1), PrimitiveValue.of(0), PrimitiveValue.of(0))));
            case "java.time.Instant": return new ObjectValue(c, new Recipe.StaticCall("java.time.Instant", "ofEpochMilli", "(J)Ljava/time/Instant;", List.of(PrimitiveValue.of(epochMillis))));
            case "java.util.Date": return new ObjectValue(c, new Recipe.ConstructorCall("java.util.Date", "(J)V", List.of(PrimitiveValue.of(epochMillis))));
            case "java.time.Year": return new ObjectValue(c, new Recipe.StaticCall("java.time.Year", "of", "(I)Ljava/time/Year;", List.of(PrimitiveValue.of(year))));
            default: return null;
        }
    }

    private static boolean isTemporal(ClassRef c) {
        return switch (c.binaryName()) {
            case "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Instant", "java.util.Date", "java.time.Year" -> true;
            default -> false;
        };
    }

    // ---------- objects ----------

    /** Field annotations declared in the class (or its scanned superclasses) constrain every instance we build of it. */
    private boolean hasFieldContracts(ClassRef c) {
        Optional<ClassTarget> ct = ctx.types().lookup(c.binaryName());
        return ct.isPresent() && allFields(ct.get()).stream().anyMatch(f -> !f.isStatic() && f.annotations().stream().anyMatch(a -> Constraints.isConstraint(a.simpleName())));
    }

    /** Declared fields of the class and of its scanned superclasses (subclass first). */
    private List<FieldInfo> allFields(ClassTarget ct) {
        List<FieldInfo> out = new ArrayList<>();
        ClassTarget cur = ct;
        for (int i = 0; cur != null && i < 6; i++) {
            for (FieldInfo f : cur.fields()) if (out.stream().noneMatch(x -> x.name().equals(f.name()))) out.add(f);
            cur = cur.superClass() == null ? null : ctx.types().lookup(cur.superClass().erasureName()).orElse(null);
        }
        return out;
    }

    /** Public instance methods of the class and of its scanned superclasses. */
    private List<MethodTarget> allMethods(ClassTarget ct) {
        List<MethodTarget> out = new ArrayList<>();
        ClassTarget cur = ct;
        for (int i = 0; cur != null && i < 6; i++) {
            for (MethodTarget m : cur.methods()) if (!m.isConstructor() && !m.isStatic()) out.add(m);
            cur = cur.superClass() == null ? null : ctx.types().lookup(cur.superClass().erasureName()).orElse(null);
        }
        return out;
    }

    private void object(Spec spec, ClassRef declared, List<Candidate> out, int depth) {
        if (depth >= MAX_DEPTH) return;
        ClassRef type = spec.subClass != null ? ClassRef.of(spec.subClass) : declared;
        Optional<ClassTarget> oct = ctx.types().lookup(type.binaryName());
        if (oct.isEmpty()) return;
        ClassTarget ct = oct.get();
        // field specs: the parameter's @Field annotations, then the class's own field annotations
        Map<String, Spec> fields = new LinkedHashMap<>(spec.fields);
        for (FieldInfo f : allFields(ct)) {
            if (f.isStatic() || fields.containsKey(f.name())) continue;
            Spec fs = Constraints.of(f.annotations());
            if (!fs.isEmpty()) fields.put(f.name(), fs);
        }
        Optional<Value> base = construct(type, 4);
        if (base.isEmpty()) return;
        if (fields.isEmpty()) {
            legal(out, base.get(), spec.subClass != null ? "@SubClass(" + ct.simpleName() + ")" : "instance");
            return;
        }
        // legal: every constrained field satisfied
        Value ok = base.get();
        List<String> applied = new ArrayList<>();
        for (var e : fields.entrySet()) {
            TypeRef ft = fieldType(ct, e.getKey());
            if (ft == null) continue;
            Optional<Value> fv = firstCandidate(e.getValue(), ft, depth + 1, true);
            if (fv.isEmpty()) continue;
            Value adjusted = withField(ok, ct, e.getKey(), fv.get());
            if (adjusted != null) { ok = adjusted; applied.add(e.getKey() + ": " + e.getValue().describe()); }
        }
        legal(out, ok, applied.isEmpty() ? "instance" : "fields satisfy " + String.join("; ", applied));
        // illegal: one field violated at a time
        for (var e : fields.entrySet()) {
            TypeRef ft = fieldType(ct, e.getKey());
            if (ft == null) continue;
            Optional<Value> bad = firstCandidate(e.getValue(), ft, depth + 1, false);
            if (bad.isEmpty()) continue;
            Value adjusted = withField(ok, ct, e.getKey(), bad.get());
            if (adjusted != null) illegal(out, adjusted, "field " + e.getKey() + " violates " + e.getValue().describe());
        }
    }

    private Optional<Value> construct(ClassRef type, int attempts) {
        for (int i = 0; i < attempts; i++) {
            Optional<Value> v = gen.generate(type);
            if (v.isPresent() && v.get() instanceof ObjectValue ov && ov.type().erasureName().equals(type.binaryName())) return v;
            if (v.isPresent() && v.get() instanceof ObjectValue) return v;
        }
        return Optional.empty();
    }

    private TypeRef fieldType(ClassTarget ct, String field) {
        for (FieldInfo f : allFields(ct)) if (f.name().equals(field)) return ctx.concreteType(f.type());
        Optional<MethodTarget> setter = setterFor(ct, field);
        return setter.map(m -> ctx.concreteType(m.params().get(0).type())).orElse(null);
    }

    private Optional<MethodTarget> setterFor(ClassTarget ct, String field) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        return allMethods(ct).stream().filter(m -> m.isPublic() && !m.isSynthetic() && m.arity() == 1 && m.name().equals(name)).findFirst();
    }

    /**
     * The object with one field set to v: through its public setter, else through a public constructor
     * that has a parameter of the field's name; null when neither exists.
     */
    private Value withField(Value obj, ClassTarget ct, String field, Value v) {
        if (!(obj instanceof ObjectValue ov)) return null;
        Optional<MethodTarget> setter = setterFor(ct, field);
        if (setter.isPresent()) {
            MethodTarget s = setter.get();
            Recipe r = ov.recipe();
            Recipe base = r instanceof Recipe.Sequence seq ? seq.base() : r;
            List<Value.Call> calls = new ArrayList<>(r instanceof Recipe.Sequence seq ? seq.mutators() : List.of());
            calls.removeIf(c -> c.methodName().equals(s.name()));
            calls.add(new Value.Call(s.declaringClass(), s.name(), s.descriptor(), List.of(v)));
            return new ObjectValue(ov.type(), new Recipe.Sequence(base, calls));
        }
        for (MethodTarget ctor : ct.methods()) {
            if (!ctor.isConstructor() || !ctor.isPublic()) continue;
            int at = -1;
            for (Parameter p : ctor.params()) if (p.name().equals(field)) at = p.index();
            if (at < 0) continue;
            List<Value> args = new ArrayList<>();
            boolean ok = true;
            for (Parameter p : ctor.params()) {
                if (p.index() == at) { args.add(v); continue; }
                Optional<Value> a = gen.generate(ctx.concreteType(p.type()));
                if (a.isEmpty()) { ok = false; break; }
                args.add(a.get());
            }
            if (!ok) continue;
            Recipe base = new Recipe.ConstructorCall(ct.binaryName(), ctor.descriptor(), args);
            Recipe r = ov.recipe();
            if (r instanceof Recipe.Sequence seq && !seq.mutators().isEmpty()) return new ObjectValue(ov.type(), new Recipe.Sequence(base, seq.mutators()));
            return new ObjectValue(ov.type(), base);
        }
        return null;
    }

    /** First legal (or illegal) candidate of a nested spec, or a plain generated value when the spec has no opinion. */
    private Optional<Value> firstCandidate(Spec spec, TypeRef t, int depth, boolean legal) {
        if (depth > MAX_DEPTH) return Optional.empty();
        for (Candidate c : candidates(spec, t, depth)) if (c.legal() == legal) return Optional.of(c.value());
        return legal ? gen.generate(t).filter(v -> !(v instanceof NullValue) || Boolean.TRUE.equals(spec.nullable)) : Optional.empty();
    }

    // ---------- helpers ----------

    private void legal(List<Candidate> out, Value v, String note) { add(out, v, true, note); }
    private void illegal(List<Candidate> out, Value v, String note) { add(out, v, false, note); }

    private void add(List<Candidate> out, Value v, boolean legal, String note) {
        for (Candidate c : out) if (c.legal() == legal && c.value().equals(v)) return;
        out.add(new Candidate(v, strategy, legal, note));
    }

    private static String firstSource(Spec spec, String dflt) {
        for (String s : spec.sources) if (!s.startsWith("@Field") && !s.startsWith("@Element")) return s;
        return dflt;
    }

    private Optional<Value> nonNull(TypeRef t) { return gen.generate(t).filter(v -> !(v instanceof NullValue)); }

    private void parse(String table, Object literal, List<Candidate> out, boolean legal, String note) {
        try {
            add(out, SpecialValues.parse(table, literal.toString()), legal, note);
        } catch (RuntimeException ignored) {
            // unparsable example: user or model input, skipped
        }
    }

    static boolean isString(TypeRef t) { return t.erasureName().equals("java.lang.String") || t.erasureName().equals("java.lang.CharSequence"); }
    static boolean isBoolean(TypeRef t) { return t.erasureName().equals("boolean") || t.erasureName().equals("java.lang.Boolean"); }
    static boolean isCollection(ClassRef c) {
        return switch (c.binaryName()) {
            case "java.util.List", "java.util.Collection", "java.util.Set", "java.util.ArrayList", "java.util.HashSet", "java.lang.Iterable" -> true;
            default -> false;
        };
    }

    private static Value bool(TypeRef t, boolean b) {
        PrimitiveValue p = PrimitiveValue.of(b);
        return t instanceof Primitive ? p : new ObjectValue(ClassRef.of("java.lang.Boolean"), new Recipe.Boxed(p));
    }
}
