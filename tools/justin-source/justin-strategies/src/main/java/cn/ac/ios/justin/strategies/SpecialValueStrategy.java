package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.EnumValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.ArrayList;
import java.util.List;

/**
 * S2: boundary and special values per type. Primitives and strings come from the SpecialValues
 * table; references add null; arrays add empty, one-element and two-element arrays of special
 * elements; collections add empty and singleton; enums add every constant. Null and empty
 * candidates are tagged illegal=false only when the parameter is annotated non-null (P1 annotation
 * strategy refines this); here everything is "legal" because we do not know the contract yet.
 */
public final class SpecialValueStrategy implements Strategy {
    public static final String NAME = "special";

    private final SpecialValues table;
    private final int maxPerSlot;

    public SpecialValueStrategy(SpecialValues table, int maxPerSlot) {
        this.table = table;
        this.maxPerSlot = maxPerSlot;
    }

    @Override public String name() { return NAME; }

    @Override
    public List<ValuePool> generate(GenerationContext ctx) {
        MethodTarget m = ctx.target();
        List<ValuePool> pools = new ArrayList<>();
        if (!m.isStatic() && !m.isConstructor()) {
            // the receiver needs a live object: special values only make sense for parameters,
            // but a receiver built from special constructor arguments is cheap to add
            ValuePool recv = new ValuePool(-1);
            ValueGenerator gen = new ValueGenerator(ctx);
            TypeRef owner = ctx.owner().typeParams().isEmpty()
                    ? ctx.owner().asType()
                    : new ClassRef(ctx.owner().binaryName(), ctx.owner().typeParams().stream().map(TypeRef.class::cast).toList());
            for (Value v : specialFor(ctx.concreteType(owner), ctx, 0)) {
                if (!(v instanceof NullValue)) recv.add(Candidate.legal(v, NAME));
            }
            if (recv.isEmpty()) gen.generate(owner).filter(v -> !(v instanceof NullValue)).ifPresent(v -> recv.add(Candidate.legal(v, NAME)));
            pools.add(recv);
        }
        for (Parameter p : m.params()) {
            ValuePool pool = new ValuePool(p.index());
            int n = 0;
            for (Value v : specialFor(ctx.concreteType(p.type()), ctx, 0)) {
                if (n++ >= maxPerSlot) break;
                pool.add(Candidate.legal(v, NAME));
            }
            pools.add(pool);
        }
        return pools;
    }

    /** Special values for a concrete type; empty when the type has no notion of "special". */
    List<Value> specialFor(TypeRef t, GenerationContext ctx, int depth) {
        List<Value> out = new ArrayList<>();
        String key = SpecialValues.tableFor(t);
        if (t instanceof Primitive) {
            out.addAll(table.get(key));
            return out;
        }
        if (t instanceof ArrayRef a) {
            TypeRef comp = a.component();
            out.add(new NullValue(t));
            out.add(new ArrayValue(comp, List.of()));
            if (depth < 2) {
                List<Value> elems = specialFor(comp, ctx, depth + 1);
                if (!elems.isEmpty()) {
                    out.add(new ArrayValue(comp, List.of(elems.get(0))));
                    if (elems.size() > 1) out.add(new ArrayValue(comp, List.of(elems.get(0), elems.get(1))));
                    if (elems.size() > 2) out.add(new ArrayValue(comp, List.of(elems.get(2), elems.get(1), elems.get(0))));
                }
            }
            return out;
        }
        if (t instanceof ClassRef c) {
            String n = c.binaryName();
            out.add(new NullValue(t));
            if (table.has(key)) {
                for (Value v : table.get(key)) {
                    if (v instanceof PrimitiveValue pv && !n.equals("java.lang.String")) {
                        out.add(new ObjectValue(ClassRef.of(pv.kind().boxed), new Recipe.Boxed(pv)));
                    } else out.add(v);
                }
                return out;
            }
            switch (n) {
                case "java.lang.CharSequence": case "java.lang.Object": case "java.lang.Comparable":
                    for (Value v : table.get("java.lang.String")) out.add(v);
                    return out;
                case "java.lang.StringBuilder": case "java.lang.StringBuffer":
                    out.add(new ObjectValue(c, new Recipe.ConstructorCall(n, "()V", List.of())));
                    out.add(new ObjectValue(c, new Recipe.ConstructorCall(n, "(Ljava/lang/String;)V", List.of(new Value.StringValue("abc")))));
                    return out;
                case "java.util.List": case "java.util.Collection": case "java.util.Iterable": case "java.util.ArrayList": {
                    TypeRef elem = c.typeArgs().isEmpty() ? TypeRef.OBJECT : ctx.concreteType(c.typeArgs().get(0));
                    out.add(collection("java.util.ArrayList", "java.util.List", elem, List.of(), ctx));
                    List<Value> elems = depth < 2 ? specialFor(elem, ctx, depth + 1) : List.of();
                    elems = elems.stream().filter(v -> !(v instanceof NullValue)).toList();
                    if (!elems.isEmpty()) out.add(collection("java.util.ArrayList", "java.util.List", elem, List.of(elems.get(0)), ctx));
                    if (elems.size() > 1) out.add(collection("java.util.ArrayList", "java.util.List", elem, List.of(elems.get(1), elems.get(0)), ctx));
                    return out;
                }
                case "java.util.Set": case "java.util.HashSet": {
                    TypeRef elem = c.typeArgs().isEmpty() ? TypeRef.OBJECT : ctx.concreteType(c.typeArgs().get(0));
                    out.add(collection("java.util.HashSet", "java.util.Set", elem, List.of(), ctx));
                    List<Value> elems = depth < 2 ? specialFor(elem, ctx, depth + 1) : List.of();
                    elems = elems.stream().filter(v -> !(v instanceof NullValue)).toList();
                    if (!elems.isEmpty()) out.add(collection("java.util.HashSet", "java.util.Set", elem, List.of(elems.get(0)), ctx));
                    return out;
                }
                case "java.util.Map": case "java.util.HashMap": {
                    TypeRef k = c.typeArgs().size() == 2 ? ctx.concreteType(c.typeArgs().get(0)) : TypeRef.OBJECT;
                    TypeRef v = c.typeArgs().size() == 2 ? ctx.concreteType(c.typeArgs().get(1)) : TypeRef.OBJECT;
                    out.add(new ObjectValue(new ClassRef("java.util.HashMap", List.of(k, v)), new Recipe.ConstructorCall("java.util.HashMap", "()V", List.of())));
                    return out;
                }
                default:
                    break;
            }
            List<String> enums = ctx.types().enumConstants(n);
            for (String e : enums) out.add(new EnumValue(n, e));
            return out;
        }
        return out;
    }

    private static Value collection(String impl, String iface, TypeRef elem, List<Value> elems, GenerationContext ctx) {
        if (elems.isEmpty()) return new ObjectValue(new ClassRef(impl, List.of(elem)), new Recipe.ConstructorCall(impl, "()V", List.of()));
        Value inner = ValueGenerator.listOf(elem, elems, iface, ctx.config().javaVersion());
        return new ObjectValue(new ClassRef(impl, List.of(elem)), new Recipe.ConstructorCall(impl, "(Ljava/util/Collection;)V", List.of(inner)));
    }
}
