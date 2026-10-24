package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.scan.ClassPathScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sootup.core.model.Body;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Same-class call inlining (plan 7.1 strategy 4) and the resumable session (plan 7.3). */
class InliningTest {

    private static SootUpLoader loader;
    private static ClassTarget delegator;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Delegator").getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        delegator = classes.stream().filter(c -> c.simpleName().equals("Delegator")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static MethodTarget method(String name) {
        return delegator.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static SymResult run(MethodTarget m, SymConfig cfg) {
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        try (SymbolicExecutor ex = new SymbolicExecutor(cfg)) {
            ex.setCalleeResolver(sig -> {
                try {
                    return loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody());
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            });
            return ex.run(m, body, Set.of());
        }
    }

    private static Set<Integer> ints(SymResult r, int argIndex) {
        Set<Integer> out = new HashSet<>();
        for (SymResult.PathInput p : r.inputs()) {
            Value v = p.args().get(argIndex);
            if (v instanceof PrimitiveValue pv) out.add((Integer) pv.boxed());
        }
        return out;
    }

    @Test
    void withoutInliningTheHelpersBranchesAreInvisible() {
        SymResult r = run(method("classify"), SymConfig.defaults().withInlineDepth(0));
        // classify itself has one branch (tag == null); score/weight return fresh symbols
        assertEquals(0, r.stats().inlinedCalls());
        assertFalse(ints(r, 0).contains(42), "code == 42 is only a constraint inside score()");
        assertTrue(r.inputs().stream().allMatch(p -> p.thrown() != null || p.imprecise()), "calls to unmodeled helpers mark the path imprecise");
    }

    @Test
    void inliningReachesTheHelpersBranches() {
        SymResult r = run(method("classify"), SymConfig.defaults().withInlineDepth(1));
        assertTrue(r.stats().inlinedCalls() > 0);
        Set<Integer> codes = ints(r, 0);
        assertTrue(codes.contains(42), "score(): code == 42 " + codes);
        assertTrue(codes.stream().anyMatch(c -> c < 0), "score(): code < 0 " + codes);
        assertTrue(codes.stream().anyMatch(c -> c > 1000), "score(): code > 1000 " + codes);
        boolean hot = false, empty = false, longTag = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value v = p.args().get(1);
            if (v instanceof Value.StringValue sv) {
                if (sv.value().startsWith("hot")) hot = true;
                if (sv.value().isEmpty()) empty = true;
                if (sv.value().length() > 5 && !sv.value().startsWith("hot")) longTag = true;
            }
        }
        assertTrue(hot && empty && longTag, "weight() branches: hot=" + hot + " empty=" + empty + " long=" + longTag);
        // returning from the inlined helpers leaves the paths precise
        assertTrue(r.inputs().stream().anyMatch(p -> !p.imprecise()));
    }

    @Test
    void instanceHelperSeesTheReceiverFields() {
        SymResult r = run(method("shifted"), SymConfig.defaults().withInlineDepth(1));
        assertTrue(r.stats().inlinedCalls() > 0);
        // clampToBase forks on x > base and x < -base with base a field symbol: three feasible paths
        assertTrue(r.stats().pathsSatisfiable() >= 3, r.stats().toString());
    }

    @Test
    void sessionCanBeResumedAndStopsWhenExhausted() {
        MethodTarget m = method("classify");
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        try (SymbolicExecutor ex = new SymbolicExecutor(SymConfig.defaults().withInlineDepth(1))) {
            ex.setCalleeResolver(sig -> loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody()));
            ex.start(m, body, Set.of());
            assertFalse(ex.finished());
            int total = 0;
            for (int i = 0; i < 100 && !ex.finished(); i++) total += ex.resume(1).size();
            assertTrue(ex.finished());
            assertNull(ex.stopReason());
            assertEquals(r(m).inputs().size(), total, "resuming in slices yields the same paths as one run");
            assertEquals(0, ex.resume(10).size(), "nothing left once finished");
        }
    }

    private static SymResult r(MethodTarget m) { return run(m, SymConfig.defaults().withInlineDepth(1)); }

    @Test
    void noGainCutStopsADirectedSearchThatReachesNothingNew() {
        MethodTarget m = method("classify");
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        // a target line that no path can touch: every satisfiable path counts as "no gain"
        try (SymbolicExecutor ex = new SymbolicExecutor(SymConfig.defaults().withInlineDepth(1).withExplosionNoGainPaths(2))) {
            ex.setCalleeResolver(sig -> loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody()));
            ex.start(m, body, Set.of(999_999));
            ex.resume(10_000);
            assertTrue(ex.finished());
            assertEquals("no-gain", ex.stopReason());
            assertTrue(ex.stats().exploded());
            assertTrue(ex.stats().pathsSatisfiable() <= 3, ex.stats().toString());
        }
    }
}
