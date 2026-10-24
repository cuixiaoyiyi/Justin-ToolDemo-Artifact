package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.ValuePool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IpogCombinerTest {

    private static MethodTarget method(int arity) {
        List<Parameter> ps = new ArrayList<>();
        for (int i = 0; i < arity; i++) ps.add(new Parameter(i, "p" + i, TypeRef.INT, List.of()));
        return new MethodTarget("a.B", "m", "(" + "I".repeat(arity) + ")V", 0x0009, List.of(), ps, TypeRef.VOID, List.of(), List.of(), 1);
    }

    private static ValuePool pool(int slot, int n) {
        ValuePool p = new ValuePool(slot);
        for (int v = 0; v < n; v++) p.add(Candidate.legal(PrimitiveValue.of(slot * 100 + v), "t"));
        return p;
    }

    /** Checks every pair of (slot, value) x (slot, value) appears in some tuple. */
    private static boolean allPairsCovered(List<InputTuple> tuples, int[] sizes) {
        for (int a = 0; a < sizes.length; a++) for (int b = a + 1; b < sizes.length; b++) {
            Set<String> seen = new HashSet<>();
            for (InputTuple t : tuples) seen.add(t.args().get(a) + "|" + t.args().get(b));
            if (seen.size() != sizes[a] * sizes[b]) return false;
        }
        return true;
    }

    @Test
    void pairwiseCoversEveryPairWithFewRows() {
        int[] sizes = {3, 3, 2, 2, 2};
        List<ValuePool> pools = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) pools.add(pool(i, sizes[i]));
        CombineResult r = new IpogCombiner(2, 16).combine(method(sizes.length), pools, Map.of(), 1000, 7);
        assertTrue(allPairsCovered(r.tuples(), sizes));
        assertEquals(1.0, r.coverage(), 1e-9);
        long ipogRows = r.tuples().stream().filter(t -> t.source().startsWith("ipog")).count();
        assertTrue(ipogRows <= 12, "ipog rows: " + ipogRows); // all-combinations would be 72; the rest is random fill
        assertEquals(2, r.strength());
    }

    @Test
    void strengthThreeAndFullStrength() {
        int[] sizes = {2, 2, 2, 2};
        List<ValuePool> pools = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) pools.add(pool(i, sizes[i]));
        CombineResult r3 = new IpogCombiner(3, 16).combine(method(4), pools, Map.of(), 1000, 1);
        assertEquals(1.0, r3.coverage(), 1e-9);
        assertTrue(r3.tuples().size() >= 8 && r3.tuples().size() <= 16);
        CombineResult r4 = new IpogCombiner(4, 16).combine(method(4), pools, Map.of(), 1000, 1);
        assertEquals(16, r4.tuples().size());
    }

    @Test
    void budgetTruncationReportsPartialCoverage() {
        List<ValuePool> pools = List.of(pool(0, 4), pool(1, 4), pool(2, 4));
        CombineResult r = new IpogCombiner(2, 16).combine(method(3), pools, Map.of(), 5, 3);
        assertEquals(5, r.tuples().size());
        assertTrue(r.coverage() < 1.0 && r.coverage() > 0.2, "coverage " + r.coverage());
    }

    @Test
    void illegalCandidatesGetTheirOwnTuples() {
        ValuePool a = pool(0, 2);
        a.add(Candidate.illegal(PrimitiveValue.of(-1), "t", "bad"));
        List<ValuePool> pools = List.of(a, pool(1, 2));
        CombineResult r = new IpogCombiner(2, 16).combine(method(2), pools, Map.of(), 10, 3);
        assertEquals(4, r.legalRows());
        assertEquals(1, r.illegalRows());
        InputTuple bad = r.tuples().get(r.tuples().size() - 1);
        assertEquals("illegal:p0", bad.source());
        assertEquals(PrimitiveValue.of(-1), bad.args().get(0));
    }

    private static ValuePool sharedPool(int slot, int n) {
        ValuePool p = new ValuePool(slot);
        for (int v = 0; v < n; v++) p.add(Candidate.legal(PrimitiveValue.of(v), "t"));
        return p;
    }

    @Test
    void constraintsAreRespected() {
        List<ValuePool> pools = List.of(sharedPool(0, 3), sharedPool(1, 3));
        // forbid p0 == p1 (same index)
        CombineResult r = new IpogCombiner(2, 16).combine(method(2), pools, Map.of(), 100, 3,
                row -> !((Value.PrimitiveValue) row.get(0).value()).boxed().equals(((Value.PrimitiveValue) row.get(1).value()).boxed()));
        for (InputTuple t : r.tuples()) assertNotEquals(t.args().get(0), t.args().get(1));
        assertEquals(6, r.tuples().size());
    }

    @Test
    void deterministic() {
        List<ValuePool> pools = List.of(pool(0, 5), pool(1, 5), pool(2, 3));
        CombineResult a = new IpogCombiner(2, 16).combine(method(3), pools, Map.of(), 20, 11);
        CombineResult b = new IpogCombiner(2, 16).combine(method(3), pools, Map.of(), 20, 11);
        assertEquals(a.tuples().stream().map(InputTuple::args).toList(), b.tuples().stream().map(InputTuple::args).toList());
    }
}
