package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S4 (seed part): constants the method body compares or computes with become candidates for
 * parameters of the matching type, with their immediate neighbours, so branches guarded by
 * "== 42" or ".equals("yes")" become reachable.
 */
public final class StaticSeedStrategy implements Strategy {
    public static final String NAME = "static";

    private final int maxPerSlot;

    public StaticSeedStrategy(int maxPerSlot) { this.maxPerSlot = maxPerSlot; }

    @Override public String name() { return NAME; }

    @Override
    public List<ValuePool> generate(GenerationContext ctx) {
        MethodTarget m = ctx.target();
        BodyFacts f = ctx.facts();
        List<ValuePool> pools = new ArrayList<>();
        if (!m.isStatic() && !m.isConstructor()) {
            ValuePool recv = new ValuePool(-1);
            new ValueGenerator(ctx).generate(ctx.owner().typeParams().isEmpty() ? ctx.owner().asType()
                    : new ClassRef(ctx.owner().binaryName(), ctx.owner().typeParams().stream().map(TypeRef.class::cast).toList()))
                    .filter(v -> !(v instanceof NullValue)).ifPresent(v -> recv.add(Candidate.legal(v, NAME)));
            pools.add(recv);
        }
        for (Parameter p : m.params()) {
            ValuePool pool = new ValuePool(p.index());
            if (f.available()) {
                for (Value v : seedsFor(ctx.concreteType(p.type()), f)) {
                    if (pool.size() >= maxPerSlot) break;
                    pool.add(Candidate.legal(v, NAME));
                }
            }
            pools.add(pool);
        }
        return pools;
    }

    static List<Value> seedsFor(TypeRef t, BodyFacts f) {
        String table = SpecialValues.tableFor(t);
        Kind k = Kind.fromKeyword(table);
        List<Value> out = new ArrayList<>();
        boolean boxed = !(t instanceof TypeRef.Primitive);
        if (k != null) {
            switch (k) {
                case INT: case SHORT: case BYTE: case CHAR: {
                    Set<Integer> vals = new LinkedHashSet<>();
                    for (int c : f.intConstants()) {
                        // skip the values every method has (0, 1, -1 come from the special strategy anyway)
                        if (c == 0 || c == 1 || c == -1) continue;
                        vals.add(c); vals.add(c - 1); vals.add(c + 1);
                    }
                    for (int v : vals) AnnotationStrategy.numeric(t, v).ifPresent(out::add);
                    break;
                }
                case LONG: {
                    Set<Long> vals = new LinkedHashSet<>();
                    for (long c : f.longConstants()) { vals.add(c); vals.add(c - 1); vals.add(c + 1); }
                    for (int c : f.intConstants()) if (c != 0 && c != 1 && c != -1) vals.add((long) c);
                    for (long v : vals) AnnotationStrategy.numeric(t, v).ifPresent(out::add);
                    break;
                }
                case DOUBLE: case FLOAT: {
                    for (double d : f.doubleConstants()) {
                        PrimitiveValue pv = k == Kind.DOUBLE ? PrimitiveValue.of(d) : PrimitiveValue.of((float) d);
                        out.add(boxed ? new ObjectValue(ClassRef.of(k.boxed), new Recipe.Boxed(pv)) : pv);
                    }
                    for (int c : f.intConstants()) if (c != 0 && c != 1) {
                        PrimitiveValue pv = k == Kind.DOUBLE ? PrimitiveValue.of((double) c) : PrimitiveValue.of((float) c);
                        out.add(boxed ? new ObjectValue(ClassRef.of(k.boxed), new Recipe.Boxed(pv)) : pv);
                    }
                    break;
                }
                default: break;
            }
            return out;
        }
        String n = t.erasureName();
        if (n.equals("java.lang.String") || n.equals("java.lang.CharSequence") || n.equals("java.lang.Object")) {
            for (String s : f.stringConstants()) {
                if (s.length() > 200) continue;
                out.add(new StringValue(s));
                if (!s.isEmpty()) { out.add(new StringValue(s + "x")); out.add(new StringValue(s.toUpperCase().equals(s) ? s.toLowerCase() : s.toUpperCase())); }
            }
            // a char constant compared with charAt(...) often appears as an int constant
            if (n.equals("java.lang.String")) {
                for (int c : f.intConstants()) if (c >= 32 && c < 127) out.add(new StringValue(String.valueOf((char) c)));
            }
        }
        return out;
    }
}
