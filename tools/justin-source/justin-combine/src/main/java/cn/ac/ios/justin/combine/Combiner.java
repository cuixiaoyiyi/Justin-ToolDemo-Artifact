package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Turns per-slot pools into input tuples under a budget. */
public interface Combiner {
    /**
     * @param pools       one pool per slot; slot -1 (receiver) first if present
     * @param maxTuples   upper bound on legal tuples returned (illegal tuples have their own budget of the same size)
     * @param allowed     rejects forbidden combinations (parameter relations); rows are candidate lists in slot order
     */
    CombineResult combine(MethodTarget target, List<ValuePool> pools, Map<String, TypeRef> typeBindings, int maxTuples, long seed,
                          Predicate<List<Candidate>> allowed);

    default CombineResult combine(MethodTarget target, List<ValuePool> pools, Map<String, TypeRef> typeBindings, int maxTuples, long seed) {
        return combine(target, pools, typeBindings, maxTuples, seed, row -> true);
    }
}
