package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Relation;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * Constraint-aware derivation: dependent parameters are excluded from the covering array and filled
 * in afterwards from the actual value of their base parameter (array length, map keys, from/to).
 * Every derivation yields satisfying values (legal) and violating values (illegal, one per tuple).
 */
public final class RelationExpander {

    private RelationExpander() {}

    /** Parameter indices that some relation derives; they take a placeholder in the combiner. */
    public static Set<Integer> dependents(List<Relation> relations) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Relation r : relations) out.add(r.dependent());
        return out;
    }

    /**
     * Replaces the dependent slots of every tuple by derived values.
     * @param pools  the original pools (used for SAME_LENGTH candidates and to know slot layout)
     */
    public static List<InputTuple> expand(MethodTarget m, List<InputTuple> tuples, List<Relation> relations, List<ValuePool> pools, int maxLegalPerTuple, int maxTotal) {
        if (relations.isEmpty()) return tuples;
        List<InputTuple> out = new ArrayList<>();
        Set<List<Value>> seen = new LinkedHashSet<>();
        for (InputTuple t : tuples) {
            if (t.source().startsWith("illegal:")) { add(out, seen, t); continue; }   // already a violation of something else
            // legal derivations: cartesian over relations, capped
            List<List<Value>> legalRows = List.of(new ArrayList<>(t.args()));
            List<String> derivedNames = new ArrayList<>();
            for (Relation r : relations) {
                List<List<Value>> next = new ArrayList<>();
                boolean derived = false;
                for (List<Value> row : legalRows) {
                    List<Value> vals = legalValues(r, row, m, pools);
                    if (vals.isEmpty()) { next.add(row); continue; }   // nothing derivable (e.g. empty base): keep the row as is
                    derived = true;
                    int n = 0;
                    for (Value v : vals) {
                        if (n++ >= maxLegalPerTuple) break;
                        List<Value> copy = new ArrayList<>(row);
                        copy.set(r.dependent(), v);
                        next.add(copy);
                    }
                }
                legalRows = next;
                if (derived) derivedNames.add(m.params().get(r.dependent()).name());
            }
            String legalSource = derivedNames.isEmpty() ? t.source() : "relation:" + String.join(",", derivedNames);
            for (List<Value> row : legalRows) {
                if (out.size() >= maxTotal) break;
                add(out, seen, new InputTuple(m, t.receiver(), row, t.typeBindings(), legalSource, t.strategies()));
            }
            // illegal derivations: one relation violated at a time, the others legal
            List<Value> base = legalRows.isEmpty() ? new ArrayList<>(t.args()) : new ArrayList<>(legalRows.get(0));
            for (Relation r : relations) {
                for (Value bad : illegalValues(r, base, m, pools)) {
                    if (out.size() >= maxTotal) break;
                    List<Value> copy = new ArrayList<>(base);
                    copy.set(r.dependent(), bad);
                    add(out, seen, new InputTuple(m, t.receiver(), copy, t.typeBindings(), "illegal:" + m.params().get(r.dependent()).name(), t.strategies()));
                }
            }
        }
        return out;
    }

    private static void add(List<InputTuple> out, Set<List<Value>> seen, InputTuple t) {
        List<Value> key = new ArrayList<>(t.args());
        key.add(t.receiver());
        if (seen.add(key)) out.add(t);
    }

    // ---------- derivations ----------

    static List<Value> legalValues(Relation r, List<Value> row, MethodTarget m, List<ValuePool> pools) {
        TypeRef depType = m.params().get(r.dependent()).type();
        Value baseVal = row.get(r.base());
        List<Value> out = new ArrayList<>();
        switch (r.kind()) {
            case INDEX_OF: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty() || len.getAsInt() == 0) return out;
                int n = len.getAsInt();
                for (int v : new TreeSet<>(List.of(0, n / 2, n - 1))) numeric(depType, v).ifPresent(out::add);
                return out;
            }
            case LENGTH_LE: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                int n = len.getAsInt();
                for (int v : new TreeSet<>(List.of(0, n / 2, n))) numeric(depType, v).ifPresent(out::add);
                return out;
            }
            case LENGTH_EQ: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                numeric(depType, len.getAsInt()).ifPresent(out::add);
                return out;
            }
            case LENGTH_GE: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                int n = len.getAsInt();
                for (int v : new TreeSet<>(List.of(n, n + 1, n + 10))) numeric(depType, v).ifPresent(out::add);
                return out;
            }
            case RANGE: {
                Optional<Long> from = longOf(baseVal);
                if (from.isEmpty()) return out;
                long f = from.get();
                for (long v : new TreeSet<>(List.of(f, f + 1, f + 5))) numeric(depType, v).ifPresent(out::add);
                return out;
            }
            case RANGE_BELOW: {
                Optional<Long> to = longOf(baseVal);
                if (to.isEmpty()) return out;
                long t = to.get();
                for (long v : new TreeSet<>(List.of(t, t - 1, t - 5))) numeric(depType, v).ifPresent(out::add);
                return out;
            }
            case KEY_OF: {
                for (Value k : keysOf(baseVal)) if (k.type().erasureName().equals(depType.erasureName()) || depType.erasureName().equals("java.lang.Object")) out.add(k);
                return out;
            }
            case SAME_LENGTH: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                for (ValuePool p : pools) {
                    if (p.slot() != r.dependent()) continue;
                    for (var c : p.candidates()) {
                        OptionalInt l = lengthOf(c.value());
                        if (l.isPresent() && l.getAsInt() == len.getAsInt()) { out.add(c.value()); if (out.size() >= 2) break; }
                    }
                }
                if (out.isEmpty() && baseVal instanceof ArrayValue a && depType instanceof TypeRef.ArrayRef d && d.component().equals(a.component())) out.add(a);
                return out;
            }
            default: return out;
        }
    }

    static List<Value> illegalValues(Relation r, List<Value> row, MethodTarget m, List<ValuePool> pools) {
        TypeRef depType = m.params().get(r.dependent()).type();
        Value baseVal = row.get(r.base());
        List<Value> out = new ArrayList<>();
        switch (r.kind()) {
            case INDEX_OF: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                numeric(depType, -1).ifPresent(out::add);
                numeric(depType, len.getAsInt()).ifPresent(out::add);
                return out;
            }
            case LENGTH_LE: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                numeric(depType, -1).ifPresent(out::add);
                numeric(depType, len.getAsInt() + 1).ifPresent(out::add);
                return out;
            }
            case LENGTH_EQ: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                int n = len.getAsInt();
                if (n > 0) numeric(depType, n - 1).ifPresent(out::add);
                numeric(depType, n + 1).ifPresent(out::add);
                numeric(depType, -1).ifPresent(out::add);
                return out;
            }
            case LENGTH_GE: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                int n = len.getAsInt();
                if (n > 0) numeric(depType, n - 1).ifPresent(out::add);
                numeric(depType, -1).ifPresent(out::add);
                return out;
            }
            case RANGE: {
                if (!r.source().startsWith("guarded")) return out;   // direction unknown: nothing is provably illegal
                Optional<Long> from = longOf(baseVal);
                if (from.isEmpty() || from.get() == Long.MIN_VALUE) return out;
                numeric(depType, from.get() - 1).ifPresent(out::add);
                return out;
            }
            case RANGE_BELOW: {
                if (!r.source().startsWith("guarded")) return out;
                Optional<Long> to = longOf(baseVal);
                if (to.isEmpty() || to.get() == Long.MAX_VALUE) return out;
                numeric(depType, to.get() + 1).ifPresent(out::add);
                return out;
            }
            case KEY_OF: {
                if (keysOf(baseVal).isEmpty() && lengthOf(baseVal).isEmpty()) return out;
                String e = depType.erasureName();
                if (e.equals("java.lang.String") || e.equals("java.lang.Object") || e.equals("java.lang.CharSequence")) out.add(new StringValue("missing-key"));
                else numeric(depType, -999_999).ifPresent(out::add);
                return out;
            }
            case SAME_LENGTH: {
                OptionalInt len = lengthOf(baseVal);
                if (len.isEmpty()) return out;
                for (ValuePool p : pools) {
                    if (p.slot() != r.dependent()) continue;
                    for (var c : p.candidates()) {
                        OptionalInt l = lengthOf(c.value());
                        if (l.isPresent() && l.getAsInt() != len.getAsInt()) { out.add(c.value()); break; }
                    }
                }
                return out;
            }
            default: return out;
        }
    }

    // ---------- value inspection ----------

    /** Number of elements/characters of a value built by our recipes, when it can be read off statically. */
    public static OptionalInt lengthOf(Value v) {
        if (v instanceof ArrayValue a) return OptionalInt.of(a.elements().size());
        if (v instanceof StringValue s) return OptionalInt.of(s.value().length());
        if (v instanceof ObjectValue o) {
            Recipe r = o.recipe();
            if (r instanceof Recipe.StaticCall sc) {
                if (sc.methodName().equals("of") || sc.methodName().equals("asList")) {
                    return sc.className().equals("java.util.Map") ? OptionalInt.of(sc.args().size() / 2) : OptionalInt.of(sc.args().size());
                }
                return OptionalInt.empty();
            }
            if (r instanceof Recipe.Sequence seq && seq.base() instanceof Recipe.ConstructorCall base && base.className().startsWith("java.util.") && base.args().isEmpty()) {
                // new HashMap()/ArrayList() followed by put/add calls: one entry per call (map keys are distinct by construction)
                long n = seq.mutators().stream().filter(c -> c.methodName().equals("put") || c.methodName().equals("add")).count();
                return OptionalInt.of((int) n);
            }
            if (r instanceof Recipe.ConstructorCall cc) {
                String cn = cc.className();
                if (cn.equals("java.lang.StringBuilder") || cn.equals("java.lang.StringBuffer")) {
                    return cc.args().size() == 1 && cc.args().get(0) instanceof StringValue s ? OptionalInt.of(s.value().length()) : OptionalInt.of(0);
                }
                if (cn.startsWith("java.util.")) {
                    if (cc.args().isEmpty()) return OptionalInt.of(0);
                    if (cc.args().size() == 1) return lengthOf(cc.args().get(0));
                }
            }
        }
        return OptionalInt.empty();
    }

    /** Keys of a map value built by our recipes. */
    static List<Value> keysOf(Value v) {
        List<Value> out = new ArrayList<>();
        if (v instanceof ObjectValue o) {
            if (o.recipe() instanceof Recipe.Sequence seq) {
                for (Value.Call c : seq.mutators()) if (c.methodName().equals("put") && c.args().size() == 2) out.add(c.args().get(0));
            } else if (o.recipe() instanceof Recipe.StaticCall sc && sc.className().equals("java.util.Map") && sc.methodName().equals("of")) {
                for (int i = 0; i + 1 < sc.args().size(); i += 2) out.add(sc.args().get(i));
            } else if (o.recipe() instanceof Recipe.ConstructorCall cc && cc.args().size() == 1) {
                return keysOf(cc.args().get(0));
            }
        }
        return out;
    }

    static Optional<Long> longOf(Value v) {
        if (v instanceof PrimitiveValue p && p.boxed() instanceof Number n) return Optional.of(n.longValue());
        if (v instanceof ObjectValue o && o.recipe() instanceof Recipe.Boxed b && b.primitive().boxed() instanceof Number n) return Optional.of(n.longValue());
        return Optional.empty();
    }

    static Optional<Value> numeric(TypeRef t, long value) {
        String e = t.erasureName();
        boolean boxed = !(t instanceof TypeRef.Primitive);
        PrimitiveValue p;
        switch (e) {
            case "int": case "java.lang.Integer": if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return Optional.empty(); p = PrimitiveValue.of((int) value); break;
            case "long": case "java.lang.Long": p = PrimitiveValue.of(value); break;
            case "short": case "java.lang.Short": if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) return Optional.empty(); p = PrimitiveValue.of((short) value); break;
            case "byte": case "java.lang.Byte": if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) return Optional.empty(); p = PrimitiveValue.of((byte) value); break;
            default: return Optional.empty();
        }
        if (!boxed) return Optional.of(p);
        return Optional.of(new ObjectValue(TypeRef.ClassRef.of(p.kind().boxed), new Recipe.Boxed(p)));
    }
}
