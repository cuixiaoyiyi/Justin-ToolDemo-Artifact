package cn.ac.ios.justin.core.contract;

import cn.ac.ios.justin.core.target.AnnotationInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The annotation basis of plan section 6 (after JNN): every third-party or Justin annotation on a
 * parameter, field or method is unified into one {@link Spec} — numeric bounds, size bounds, regular
 * expressions, nullability, boolean value, temporal direction, runtime subclass, examples — and the
 * structural annotations {@code @Field}, {@code @Element}, {@code @SubClass} compose specs
 * recursively: the constraint annotations that follow a structural one apply to the selected
 * field or element. Annotations are read left to right; a second {@code @Field} (or {@code @Element})
 * starts a sibling of the previous one.
 */
public final class Constraints {
    private Constraints() {}

    /** Constraints on one value; nested specs for fields and elements. All fields are optional. */
    public static final class Spec {
        public Long min, max;
        public boolean minExclusive, maxExclusive;
        public Double minReal, maxReal;
        public Integer sizeMin, sizeMax;
        public final List<String> patterns = new ArrayList<>();
        /** TRUE: null is a legal value; FALSE: null is illegal; null: unknown. */
        public Boolean nullable;
        public boolean mustBeNull;
        public boolean notBlank;
        public Boolean bool;
        /** "past", "pastOrPresent", "future", "futureOrPresent" or null. */
        public String temporal;
        public String subClass;
        /** Length relation to another parameter (@ArrayPara / @IndexOf): base parameter index and flag, or null. */
        public Integer arrayParaIndex, arrayParaFlag;
        public String arrayParaName;
        public final List<Object> examples = new ArrayList<>();
        public final List<Object> invalid = new ArrayList<>();
        public final Map<String, Spec> fields = new LinkedHashMap<>();
        public final Map<Integer, Spec> elements = new LinkedHashMap<>();
        /** Human-readable origins, e.g. "@Min(5)", used in candidate notes and assertion messages. */
        public final List<String> sources = new ArrayList<>();
        /** True when at least one contributing annotation was inferred by the model. */
        public boolean fromModel;
        public boolean declared;

        public boolean hasNumericBound() { return min != null || max != null || minReal != null || maxReal != null; }
        public boolean hasSize() { return sizeMin != null || sizeMax != null; }
        public boolean isEmpty() {
            return !hasNumericBound() && !hasSize() && patterns.isEmpty() && nullable == null && !mustBeNull && !notBlank && bool == null
                    && temporal == null && subClass == null && arrayParaIndex == null && arrayParaName == null && examples.isEmpty() && invalid.isEmpty()
                    && fields.isEmpty() && elements.isEmpty();
        }
        public String describe() { return String.join(" ", sources); }

        void source(String s) { if (!sources.contains(s)) sources.add(s); }
        void lower(long v, boolean exclusive) { if (min == null || v > min || v == min && exclusive) { min = v; minExclusive = exclusive; } }
        void upper(long v, boolean exclusive) { if (max == null || v < max || v == max && exclusive) { max = v; maxExclusive = exclusive; } }
    }

    /** Structural annotations select the target of the constraints that follow them. */
    public static boolean isStructural(String simpleName) {
        return simpleName.equals("Field") || simpleName.equals("Element");
    }

    /** True for annotations this class understands (so callers can tell contract annotations from noise). */
    public static boolean isConstraint(String simpleName) {
        switch (simpleName) {
            case "Min": case "DecimalMin": case "Max": case "DecimalMax": case "Positive": case "PositiveOrZero": case "Negative": case "NegativeOrZero":
            case "InRange": case "Range": case "Digits": case "Nonnegative": case "Size": case "Length": case "NotEmpty": case "NotBlank": case "Null":
            case "NotNull": case "NonNull": case "Nonnull": case "Nullable": case "CheckForNull": case "Pattern": case "Email": case "URL": case "UUID":
            case "CreditCardNumber": case "AssertTrue": case "AssertFalse": case "Past": case "PastOrPresent": case "Future": case "FutureOrPresent":
            case "Examples": case "IndexOf": case "ArrayPara": case "SubClass": case "Field": case "Element":
                return true;
            default:
                return false;
        }
    }

    /** Unifies an annotation list (left to right) into a spec tree; unknown annotations are ignored. */
    public static Spec of(List<AnnotationInfo> annotations) {
        Spec root = new Spec();
        Spec cursor = root;
        // path from root to cursor; the last entry's kind says whether a new @Field/@Element is a sibling
        List<Spec> path = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        path.add(root); kinds.add("root");
        for (AnnotationInfo a : annotations) {
            String simple = a.simpleName();
            Map<String, Object> v = a.values();
            boolean model = a.binaryName().startsWith("justin.llm.");
            if (simple.equals("Field") || simple.equals("Element")) {
                if (simple.equals("Field") && v.get("in") instanceof Number in) {
                    // explicit placement (model output): a field of element `in`
                    path.subList(1, path.size()).clear(); kinds.subList(1, kinds.size()).clear();
                    Spec el = root.elements.computeIfAbsent(in.intValue(), k -> new Spec());
                    path.add(el); kinds.add("Element");
                } else if (kinds.get(kinds.size() - 1).equals(simple)) { path.remove(path.size() - 1); kinds.remove(kinds.size() - 1); }
                Spec parent = path.get(path.size() - 1);
                Spec child;
                if (simple.equals("Field")) {
                    String name = String.valueOf(v.getOrDefault("value", ""));
                    if (name.isEmpty()) continue;
                    child = parent.fields.computeIfAbsent(name, k -> new Spec());
                    parent.source("@Field(\"" + name + "\")");
                } else {
                    int idx = (int) asLong(v.get("value"), 0);
                    if (idx < 0) continue;
                    child = parent.elements.computeIfAbsent(idx, k -> new Spec());
                    parent.source("@Element(" + idx + ")");
                }
                if (model) { child.fromModel = true; parent.fromModel = true; } else { child.declared = true; parent.declared = true; }
                path.add(child); kinds.add(simple);
                cursor = child;
                continue;
            }
            if (apply(cursor, simple, v)) {
                if (model) cursor.fromModel = true; else cursor.declared = true;
                for (Spec s : path) if (model) s.fromModel = true; else s.declared = true;
            }
        }
        return root;
    }

    /** Applies one constraint annotation to the spec; returns false when the annotation is not understood. */
    static boolean apply(Spec s, String simple, Map<String, Object> v) {
        switch (simple) {
            case "Min": case "DecimalMin": {
                Object val = v.get("value");
                boolean inclusive = !Boolean.FALSE.equals(v.get("inclusive"));
                if (isReal(val)) { s.minReal = asDouble(val); s.source("@" + simple + "(" + val + ")"); return true; }
                long min = asLong(val, 0);
                s.lower(min, !inclusive);
                s.source("@" + simple + "(" + min + ")");
                return true;
            }
            case "Max": case "DecimalMax": {
                Object val = v.get("value");
                boolean inclusive = !Boolean.FALSE.equals(v.get("inclusive"));
                if (isReal(val)) { s.maxReal = asDouble(val); s.source("@" + simple + "(" + val + ")"); return true; }
                long max = asLong(val, 0);
                s.upper(max, !inclusive);
                s.source("@" + simple + "(" + max + ")");
                return true;
            }
            case "Positive": s.lower(1, false); s.source("@Positive"); return true;
            case "PositiveOrZero": case "Nonnegative": s.lower(0, false); s.source("@" + simple); return true;
            case "Negative": s.upper(-1, false); s.source("@Negative"); return true;
            case "NegativeOrZero": s.upper(0, false); s.source("@NegativeOrZero"); return true;
            case "InRange": case "Range": {
                // Justin @InRange(min,max), Hibernate @Range(min,max), JetBrains @Range(from,to)
                Object lo = v.containsKey("min") ? v.get("min") : v.get("from");
                Object hi = v.containsKey("max") ? v.get("max") : v.get("to");
                boolean any = false;
                if (lo != null && asLong(lo, Long.MIN_VALUE) != Long.MIN_VALUE) { s.lower(asLong(lo, 0), false); any = true; }
                if (hi != null && asLong(hi, Long.MAX_VALUE) != Long.MAX_VALUE) { s.upper(asLong(hi, 0), false); any = true; }
                if (any) s.source("@" + simple + "(" + (lo == null ? "" : lo) + ".." + (hi == null ? "" : hi) + ")");
                return any;
            }
            case "Digits": {
                long integer = asLong(v.get("integer"), 0);
                if (integer <= 0 || integer > 18) return false;
                long bound = (long) Math.pow(10, integer) - 1;
                s.lower(-bound, false); s.upper(bound, false);
                s.source("@Digits(integer=" + integer + ")");
                return true;
            }
            case "Size": case "Length": {
                if (v.containsKey("min")) s.sizeMin = (int) asLong(v.get("min"), 0);
                if (v.containsKey("max")) s.sizeMax = (int) asLong(v.get("max"), Integer.MAX_VALUE);
                s.source("@" + simple + "(" + (s.sizeMin == null ? "" : "min=" + s.sizeMin) + (s.sizeMax == null ? "" : (s.sizeMin == null ? "" : ",") + "max=" + s.sizeMax) + ")");
                return true;
            }
            case "NotEmpty": s.sizeMin = s.sizeMin == null ? 1 : Math.max(1, s.sizeMin); s.nullable = Boolean.FALSE; s.source("@NotEmpty"); return true;
            case "NotBlank": s.notBlank = true; s.nullable = Boolean.FALSE; s.source("@NotBlank"); return true;
            case "Null": s.mustBeNull = true; s.nullable = Boolean.TRUE; s.source("@Null"); return true;
            case "NotNull": case "NonNull": case "Nonnull": s.nullable = Boolean.FALSE; s.source("@" + simple); return true;
            case "Nullable": case "CheckForNull": if (s.nullable == null) s.nullable = Boolean.TRUE; s.source("@" + simple); return true;
            case "Pattern": {
                Object re = v.containsKey("regexp") ? v.get("regexp") : v.get("value");
                if (re == null || re.toString().isEmpty()) return false;
                s.patterns.add(re.toString());
                s.source("@Pattern(\"" + re + "\")");
                return true;
            }
            case "Email": s.patterns.add("[a-z][a-z0-9.]{2,8}@[a-z]{3,8}\\.(com|org|net)"); s.source("@Email"); return true;
            case "URL": s.patterns.add("https?://[a-z]{3,8}\\.(com|org|net)(/[a-z0-9]{1,6}){0,2}"); s.source("@URL"); return true;
            case "UUID": s.patterns.add("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"); s.source("@UUID"); return true;
            case "CreditCardNumber": s.examples.add("\"4111111111111111\""); s.examples.add("\"5500000000000004\""); s.invalid.add("\"4111111111111112\""); s.source("@CreditCardNumber"); return true;
            case "AssertTrue": s.bool = Boolean.TRUE; s.source("@AssertTrue"); return true;
            case "AssertFalse": s.bool = Boolean.FALSE; s.source("@AssertFalse"); return true;
            case "Past": case "PastOrPresent": case "Future": case "FutureOrPresent":
                s.temporal = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
                s.source("@" + simple);
                return true;
            case "SubClass": {
                Object c = v.get("value");
                if (c == null) return false;
                String name = c.toString();
                if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1).replace('/', '.');
                s.subClass = name;
                s.source("@SubClass(" + name.substring(name.lastIndexOf('.') + 1) + ")");
                return true;
            }
            case "Examples": {
                for (Object o : list(v.get("value"))) s.examples.add(o);
                for (Object o : list(v.get("invalid"))) s.invalid.add(o);
                s.source("@Examples");
                return true;
            }
            case "IndexOf": {
                Object t = v.get("value");
                if (t == null) return false;
                s.arrayParaName = t.toString();
                s.arrayParaFlag = -2; // strict index: 0 <= x < length
                s.source("@IndexOf(\"" + t + "\")");
                return true;
            }
            case "ArrayPara": {
                s.arrayParaIndex = (int) asLong(v.get("paraIndex"), -1);
                s.arrayParaFlag = (int) asLong(v.get("flag"), 0);
                if (s.arrayParaIndex < 0) { s.arrayParaIndex = null; return false; }
                s.source("@ArrayPara(" + s.arrayParaIndex + "," + s.arrayParaFlag + ")");
                return true;
            }
            default:
                return false;
        }
    }

    static long asLong(Object o, long dflt) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String str) { try { return (long) Double.parseDouble(str); } catch (NumberFormatException e) { return dflt; } }
        return dflt;
    }

    static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    /** DecimalMin/DecimalMax carry strings; a value with a fraction is a real bound. */
    static boolean isReal(Object o) {
        if (o instanceof Double || o instanceof Float) return true;
        if (o instanceof String str) return str.contains(".") || str.contains("e") || str.contains("E");
        return false;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o instanceof List<?> l) return (List<Object>) l;
        if (o == null) return List.of();
        return List.of(o);
    }
}
