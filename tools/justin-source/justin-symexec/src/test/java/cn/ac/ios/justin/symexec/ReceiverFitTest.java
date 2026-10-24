package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.scan.ClassPathScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sootup.core.model.Body;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Completed paths take the field values of a concrete receiver (sample class Meter). */
class ReceiverFitTest {
    private static final String M = "cn.ac.ios.justin.samples.Meter#";

    private static SootUpLoader loader;
    private static ClassTarget meter;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Meter").getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        meter = classes.stream().filter(c -> c.simpleName().equals("Meter")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static MethodTarget method(String name) {
        return meter.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    /** Receivers given by the values of their fields. */
    private static ReceiverStates states(List<Map<String, Object>> candidates, Map<String, Object> statics) {
        return new ReceiverStates() {
            @Override public int candidates() { return candidates.size(); }
            @Override public Object read(int k, List<String> path) {
                String key = String.join("/", path);
                if (key.startsWith("static:")) return statics.getOrDefault(key, UNKNOWN);
                Map<String, Object> c = candidates.get(k);
                return c.containsKey(key) ? c.get(key) : UNKNOWN;
            }
        };
    }

    private static Map<String, Object> fields(int limit, int level, boolean locked, String label, int count, int[] history) {
        Map<String, Object> f = new HashMap<>();
        f.put(M + "limit", limit);
        f.put(M + "level", level);
        f.put(M + "locked", locked);
        f.put(M + "label", label);
        f.put(M + "count", count);
        f.put(M + "history", history);
        return f;
    }

    private record Run(List<SymResult.PathInput> inputs, SymResult.Stats stats, Map<String, Integer> unmet) {}

    private static Run run(String name, ReceiverStates states) {
        MethodTarget m = method(name);
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        try (SymbolicExecutor ex = new SymbolicExecutor(SymConfig.defaults())) {
            ex.setCalleeResolver(sig -> {
                try {
                    return loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody());
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            });
            ex.setReceiverStates(states);
            ex.start(m, body, Set.of());
            List<SymResult.PathInput> all = new ArrayList<>(ex.resume(20_000));
            return new Run(all, ex.stats(), ex.unmetFields());
        }
    }

    private static int arg(SymResult.PathInput p) { return (Integer) ((PrimitiveValue) p.args().get(0)).boxed(); }

    @Test
    void withoutCandidatesFieldsAreFree() {
        Run r = run("status", null);
        assertTrue(r.inputs().size() >= 4, r.stats().toString());
        assertTrue(r.inputs().stream().allMatch(p -> p.receiver() == -1));
        assertEquals(0, r.stats().receiver().paths());
    }

    @Test
    void aPathTakesTheCandidateItIsFeasibleFor() {
        // candidate 0: fresh meter with limit 5; candidate 1: locked; candidate 2: level 3 and a label that starts with x
        Run r = run("status", states(List.of(
                fields(5, 0, false, null, 0, new int[4]),
                fields(5, 3, true, null, 1, new int[] {3, 0, 0, 0}),
                fields(7, 3, false, "xy", 1, new int[] {3, 0, 0, 0})), Map.of()));
        Set<Integer> used = new java.util.TreeSet<>();
        for (SymResult.PathInput p : r.inputs()) used.add(p.receiver());
        assertTrue(used.containsAll(Set.of(0, 1, 2)), "receivers used: " + used + " " + r.stats());
        for (SymResult.PathInput p : r.inputs()) {
            // "over" on candidate 0 needs margin > 5, on candidate 2 margin > 4: the argument follows the receiver
            if (p.receiver() == 0) assertTrue(arg(p) > 5 || arg(p) <= 5, "any margin is consistent with a fresh meter");
        }
        assertTrue(r.inputs().stream().anyMatch(p -> p.receiver() == 0 && arg(p) > 5), "over, on the fresh meter");
        assertTrue(r.stats().receiver().fitted() >= 4, r.stats().toString());
    }

    @Test
    void pathsNoCandidateFitsAreCountedWithTheirFields() {
        // only a fresh meter: locked and the label branch are out of reach
        Run r = run("status", states(List.of(fields(5, 0, false, null, 0, new int[4])), Map.of()));
        assertTrue(r.stats().receiver().unfitted() >= 2, r.stats().toString());
        assertTrue(r.unmet().containsKey(M + "locked"), r.unmet().toString());
        assertTrue(r.unmet().containsKey(M + "label"), r.unmet().toString());
        assertTrue(r.inputs().stream().anyMatch(p -> p.receiver() == 0));
    }

    @Test
    void arrayFieldsTakeTheirElements() {
        Run r = run("recent", states(List.of(
                fields(5, 0, false, null, 0, new int[4]),
                fields(50, 12, false, null, 2, new int[] {5, 7, 0, 0})), Map.of()));
        assertTrue(r.inputs().stream().anyMatch(p -> p.receiver() == 1 && arg(p) == 7), "history[1] == n needs n = 7 on the second candidate: " + r.inputs());
        assertTrue(r.inputs().stream().anyMatch(p -> p.receiver() == 0), "the fresh meter takes the other path");
    }

    @Test
    void staticFieldsTakeTheirValues() {
        Run free = run("step", states(List.of(), Map.of()));
        Run fixed = run("step", states(List.of(), Map.of("static:" + M + "STEPS", new int[] {10, 100, 1000})));
        assertTrue(fixed.stats().receiver().withStatics() > 0, fixed.stats().toString());
        // STEPS[i] == 100 holds for i = 1 only, and the array has three elements
        int past = Integer.MAX_VALUE, equal = Integer.MAX_VALUE;
        for (SymResult.PathInput p : fixed.inputs()) {
            if (p.lines().size() == 1) continue;                 // the range check sent it back
            past = Math.min(past, p.lines().size());
            assertTrue(arg(p) >= 0 && arg(p) <= 2, "past the range check: " + p);
            if (p.lines().size() == 2) { equal = arg(p); }        // returned from the comparison with 100
        }
        assertEquals(1, equal, fixed.inputs().toString());
        assertFalse(free.inputs().isEmpty());
    }

    private static Run runAfter(String name, List<String> before, ReceiverStates states) {
        MethodTarget m = method(name);
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        List<SymbolicExecutor.Prefix> calls = new ArrayList<>();
        for (String b : before) calls.add(new SymbolicExecutor.Prefix(method(b), loader.loadMethod(method(b)).orElseThrow().getBody()));
        try (SymbolicExecutor ex = new SymbolicExecutor(SymConfig.defaults())) {
            ex.setReceiverStates(states);
            ex.start(m, body, Set.of(), calls);
            List<SymResult.PathInput> all = new ArrayList<>(ex.resume(20_000));
            return new Run(all, ex.stats(), ex.unmetFields());
        }
    }

    @Test
    void aCallBeforeTheMethodBuildsTheStateNoCandidateHas() {
        ReceiverStates fresh = states(List.of(fields(5, 0, false, null, 0, new int[4])), Map.of());
        Run locked = runAfter("status", List.of("lock"), fresh);
        // after lock() every path of status() ends at its first return, on the fresh meter
        assertFalse(locked.inputs().isEmpty());
        // and without reading a field as it was on entry: any receiver will do, the default one is taken
        assertTrue(locked.inputs().stream().allMatch(p -> p.receiver() <= 0 && p.lines().size() == 1), locked.inputs().toString());
        assertEquals(0, locked.stats().receiver().unfitted());
    }

    @Test
    void theArgumentsOfTheCallsBeforeAreSolvedWithThoseOfTheMethod() {
        ReceiverStates fresh = states(List.of(fields(5, 0, false, null, 0, new int[4])), Map.of());
        Run r = runAfter("recent", List.of("add", "add"), fresh);
        boolean found = false;
        for (SymResult.PathInput p : r.inputs()) {
            assertEquals(2, p.before().size());
            int first = (Integer) ((PrimitiveValue) p.before().get(0).get(0)).boxed(), second = (Integer) ((PrimitiveValue) p.before().get(1).get(0)).boxed();
            assertTrue(first >= 0 && second >= 0, "add() throws on a negative number, such a path builds no receiver: " + p);
            assertEquals(0, p.receiver(), "paths that need another entry state are not reported for a sequence: " + p);
            // one line: the condition held, the method returned from the line of the condition
            if (p.lines().size() == 1 && p.thrown() == null && arg(p) == second) found = true;
        }
        assertTrue(found, "history[1] == n with n the second number added: " + r.inputs());
    }

    @Test
    void writersOfTheFieldsAMethodReads() {
        FieldUse use = new FieldUse(sig -> loader.view().getMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody()));
        FieldUse.Use status = use.of(loader.loadMethod(method("status")).orElseThrow().getBody());
        assertTrue(status.reads().containsAll(Set.of(M + "locked", M + "level", M + "limit", M + "label")), status.toString());
        assertTrue(status.writes().isEmpty(), status.toString());
        FieldUse.Use add = use.of(loader.loadMethod(method("add")).orElseThrow().getBody());
        assertTrue(add.writes().containsAll(Set.of(M + "level", M + "count", M + "history")), add.toString());
        FieldUse.Use note = use.of(loader.loadMethod(method("note")).orElseThrow().getBody());
        assertTrue(note.writes().contains(M + "notes"), "adding to the list a field holds changes the state behind the field: " + note);
    }
}
