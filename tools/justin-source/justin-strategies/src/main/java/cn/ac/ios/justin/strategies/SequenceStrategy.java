package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S6: state-building sequences. Containers behave differently when they are empty, hold one element
 * or hold several, so the receiver and every container-like parameter — arrays, JDK collections and
 * maps, project classes with add/put/push/offer/append/insert methods — get one candidate per fill
 * level. Elements are typed by the method's type-variable bindings, the same ones the receiver was
 * built with. Reference arrays additionally get a variant with a null element.
 */
public final class SequenceStrategy implements Strategy {
    public static final String NAME = "sequence";
    private static final int[] LEVELS = {0, 1, 3};

    @Override public String name() { return NAME; }

    @Override
    public List<ValuePool> generate(GenerationContext ctx) {
        MethodTarget m = ctx.target();
        ValueGenerator gen = new ValueGenerator(ctx);
        List<ValuePool> pools = new ArrayList<>();
        if (!m.isStatic() && !m.isConstructor()) {
            ValuePool recv = new ValuePool(-1);
            TypeRef owner = ctx.owner().typeParams().isEmpty()
                    ? ctx.owner().asType()
                    : new TypeRef.ClassRef(ctx.owner().binaryName(), ctx.owner().typeParams().stream().map(TypeRef.class::cast).toList());
            fill(gen, owner, recv, false);
            pools.add(recv);
        }
        for (Parameter p : m.params()) {
            ValuePool pool = new ValuePool(p.index());
            fill(gen, p.type(), pool, true);
            pools.add(pool);
        }
        return pools;
    }

    private void fill(ValueGenerator gen, TypeRef type, ValuePool pool, boolean nullElement) {
        for (int level : LEVELS) {
            Optional<Value> v = gen.filled(type, level);
            if (v.isEmpty() || v.get() instanceof Value.NullValue) continue;
            pool.add(new Candidate(v.get(), NAME, true, level == 0 ? "empty" : level + " element" + (level > 1 ? "s" : "")));
            // a reference array with a hole: legal input that many loops forget about
            if (nullElement && level == 3 && v.get() instanceof Value.ArrayValue a && !(a.component() instanceof TypeRef.Primitive) && !a.elements().isEmpty()) {
                List<Value> holed = new ArrayList<>(a.elements());
                holed.set(holed.size() / 2, new Value.NullValue(a.component()));
                pool.add(new Candidate(new Value.ArrayValue(a.component(), holed), NAME, true, "null element"));
            }
        }
    }
}
