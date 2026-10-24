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

/** S1: type-directed random values, a fixed number of candidates per slot. */
public final class RandomStrategy implements Strategy {
    public static final String NAME = "random";

    private final int candidatesPerSlot;

    public RandomStrategy(int candidatesPerSlot) { this.candidatesPerSlot = candidatesPerSlot; }

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
            for (int i = 0; i < candidatesPerSlot; i++) {
                Optional<Value> v = gen.generate(owner);
                if (v.isPresent() && !(v.get() instanceof Value.NullValue)) recv.add(Candidate.legal(v.get(), NAME));
            }
            pools.add(recv);
        }
        for (Parameter p : m.params()) {
            ValuePool pool = new ValuePool(p.index());
            for (int i = 0; i < candidatesPerSlot; i++) {
                gen.generate(p.type()).ifPresent(v -> pool.add(Candidate.legal(v, NAME)));
            }
            pools.add(pool);
        }
        return pools;
    }
}
