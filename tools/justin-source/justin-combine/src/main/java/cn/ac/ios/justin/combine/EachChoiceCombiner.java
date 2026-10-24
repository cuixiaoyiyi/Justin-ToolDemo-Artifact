package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Strength-1 covering (every candidate at least once): IPOG with strength 1. Kept as a named entry point. */
public final class EachChoiceCombiner implements Combiner {
    private final IpogCombiner delegate = new IpogCombiner(1, Integer.MAX_VALUE);

    @Override
    public CombineResult combine(MethodTarget target, List<ValuePool> pools, Map<String, TypeRef> typeBindings, int maxTuples, long seed,
                                 Predicate<List<Candidate>> allowed) {
        return delegate.combine(target, pools, typeBindings, maxTuples, seed, allowed);
    }
}
