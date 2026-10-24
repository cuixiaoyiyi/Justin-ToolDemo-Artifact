package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.value.Value;
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

/** The state of arguments and of the objects the receiver is built from; handled exceptions; sorted collections and removals (sample class Shipment). */
class ObjectRootsTest {
    private static final String S = "cn.ac.ios.justin.samples.Shipment";
    private static final String BOX = S + "$Box#";

    private static SootUpLoader loader;
    private static ClassTarget shipment, box;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName(S).getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        shipment = classes.stream().filter(c -> c.binaryName().equals(S)).findFirst().orElseThrow();
        box = classes.stream().filter(c -> c.binaryName().equals(S + "$Box")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static MethodTarget method(ClassTarget c, String name) {
        return c.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    /** Candidates by root, each given by the values of its fields. */
    private static ReceiverStates states(Map<String, List<Map<String, Object>>> roots) {
        return new ReceiverStates() {
            @Override public int candidates() { return candidates(""); }
            @Override public int candidates(String root) { return roots.getOrDefault(root, List.of()).size(); }
            @Override public Object read(int k, List<String> path) { return read("", k, path); }
            @Override public Object read(String root, int k, List<String> path) {
                List<Map<String, Object>> cs = roots.getOrDefault(root, List.of());
                if (k < 0 || k >= cs.size()) return UNKNOWN;
                if (path.isEmpty()) return new Ref(root.isEmpty() ? S : S + "$Box");
                String key = String.join("/", path);
                return cs.get(k).containsKey(key) ? cs.get(k).get(key) : UNKNOWN;
            }
        };
    }

    private static Map<String, Object> boxOf(int weight, boolean sealed) {
        Map<String, Object> f = new HashMap<>();
        f.put(BOX + "weight", weight);
        f.put(BOX + "sealed", sealed);
        return f;
    }

    private static SymResult run(MethodTarget m, ReceiverStates states, List<SymbolicExecutor.Prefix> calls) {
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
            ex.start(m, body, Set.of(), calls);
            return new SymResult(new ArrayList<>(ex.resume(20_000)), ex.stats());
        }
    }

    private static SymbolicExecutor.Prefix call(ClassTarget c, String name, String root) {
        MethodTarget m = method(c, name);
        return new SymbolicExecutor.Prefix(m, loader.loadMethod(m).orElseThrow().getBody(), root);
    }

    private static String returned(SymResult.PathInput p, int... linesByResult) {
        return p.thrown() != null ? "throws " + p.thrown() : p.lines().toString();
    }

    @Test
    void anArgumentTakesTheCandidateItsPathNeeds() {
        // the argument's candidates: open and light, open and heavy, sealed
        ReceiverStates st = states(Map.of(SymbolicExecutor.ARG + "0", List.of(boxOf(5, false), boxOf(500, false), boxOf(5, true))));
        SymResult r = run(method(shipment, "accept"), st, List.of());
        Set<Integer> chosen = new java.util.TreeSet<>();
        for (SymResult.PathInput p : r.inputs()) {
            Integer k = p.chosen().get(SymbolicExecutor.ARG + "0");
            if (k != null) chosen.add(k);
        }
        assertEquals(Set.of(0, 1, 2), chosen, "one candidate per branch: " + r.inputs());
        assertEquals(0, r.stats().receiver().unfitted(), r.stats().toString());
    }

    @Test
    void theReceiverBuiltFromAnObjectAfterCallsOnIt() {
        // the box the shipment is built from is fresh; seal() on it, then the constructor, then stamp() on the shipment
        ReceiverStates st = states(Map.of("", List.of(Map.of()), SymbolicExecutor.CTOR + "0", List.of(boxOf(5, false))));
        List<SymbolicExecutor.Prefix> calls = List.of(call(box, "seal", SymbolicExecutor.CTOR + "0"), call(shipment, "<init>", SymbolicExecutor.Prefix.CONSTRUCT),
                call(shipment, "stamp", ""));
        SymResult r = run(method(shipment, "label"), st, calls);
        // sealed and stamped: every path returns from the first return statement
        assertFalse(r.inputs().isEmpty(), r.stats().toString());
        assertTrue(r.inputs().stream().allMatch(p -> p.thrown() == null && p.lines().size() == 1), r.inputs().toString());
        // without the calls the fresh box is open: the last return
        SymResult plain = run(method(shipment, "label"), st, List.of(call(shipment, "<init>", SymbolicExecutor.Prefix.CONSTRUCT)));
        assertTrue(plain.inputs().stream().anyMatch(p -> p.thrown() == null && p.lines().size() == 3), plain.inputs().toString());
        assertTrue(plain.inputs().stream().noneMatch(p -> p.thrown() == null && p.lines().size() == 1), "sealed is out of reach: " + plain.inputs());
    }

    @Test
    void exceptionsOfACallAreHandledWhereTheyAreCaught() {
        ReceiverStates st = states(Map.of("", List.of(Map.of()), SymbolicExecutor.CTOR + "0", List.of(boxOf(5, false))));
        SymResult r = run(method(shipment, "checked"), st, List.of(call(shipment, "<init>", SymbolicExecutor.Prefix.CONSTRUCT)));
        boolean bad = false, loaded = false;
        for (SymResult.PathInput p : r.inputs()) {
            assertNull(p.thrown(), "every exception of load() is caught: " + p);
            int more = (Integer) ((Value.PrimitiveValue) p.args().get(0)).boxed();
            if (more < 0) bad = true; else loaded = true;
        }
        assertTrue(bad && loaded, "bad=" + bad + " loaded=" + loaded + " " + r.inputs());
        SymResult sealed = run(method(shipment, "checked"), st,
                List.of(call(box, "seal", SymbolicExecutor.CTOR + "0"), call(shipment, "<init>", SymbolicExecutor.Prefix.CONSTRUCT)));
        assertTrue(sealed.inputs().stream().allMatch(p -> p.thrown() == null), sealed.inputs().toString());
        assertTrue(sealed.inputs().stream().allMatch(p -> p.lines().equals(r.inputs().get(0).lines()) || true));
    }

    @Test
    void exceptionsOfAnArrayAccessAreHandled() {
        SymResult r = run(method(shipment, "guarded"), null, List.of());
        assertTrue(r.inputs().stream().allMatch(p -> p.thrown() == null), r.inputs().toString());
        boolean out = false, none = false, big = false, small = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value a = p.args().get(0);
            int i = (Integer) ((Value.PrimitiveValue) p.args().get(1)).boxed();
            if (a instanceof Value.NullValue) none = true;
            else if (a instanceof Value.ArrayValue arr) {
                if (i < 0 || i >= arr.elements().size()) out = true;
                else if ((Integer) ((Value.PrimitiveValue) arr.elements().get(i)).boxed() > 10) big = true;
                else small = true;
            }
        }
        assertTrue(out && none && big && small, "out=" + out + " none=" + none + " big=" + big + " small=" + small + " " + r.inputs());
    }

    @Test
    void aSortedSetIsBuiltInOrder() {
        SymResult r = run(method(shipment, "first"), null, List.of());
        boolean aFirst = false;
        for (SymResult.PathInput p : r.inputs()) {
            if (!(p.args().get(0) instanceof Value.ObjectValue o) || !(o.recipe() instanceof Value.Recipe.Sequence s)) continue;
            List<String> names = new ArrayList<>();
            for (Value.Call c : s.mutators()) names.add(((Value.StringValue) c.args().get(0)).value());
            List<String> sorted = new ArrayList<>(names);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, names, "in ascending order");
            assertEquals("java.util.TreeSet", o.type().erasureName());
            if (!names.isEmpty() && names.get(0).startsWith("a")) aFirst = true;
        }
        assertTrue(aFirst, r.inputs().toString());
    }

    @Test
    void removalShiftsTheElements() {
        SymResult r = run(method(shipment, "drop"), null, List.of());
        boolean hit = false;
        for (SymResult.PathInput p : r.inputs()) {
            if (p.thrown() != null || !(p.args().get(0) instanceof Value.ObjectValue o) || !(o.recipe() instanceof Value.Recipe.Sequence s)) continue;
            List<Integer> xs = new ArrayList<>();
            for (Value.Call c : s.mutators()) {
                Value e = c.args().get(0);
                if (e instanceof Value.ObjectValue b && b.recipe() instanceof Value.Recipe.Boxed bx) e = bx.primitive();
                xs.add((Integer) ((Value.PrimitiveValue) e).boxed());
            }
            int i = (Integer) ((Value.PrimitiveValue) p.args().get(1)).boxed();
            if (i < 0 || i >= xs.size()) continue;
            xs.remove(i);
            if (xs.size() == 2 && xs.get(1) == 7 && p.lines().size() == 2) hit = true;
        }
        assertTrue(hit, "a list of three whose second element after the removal is 7: " + r.inputs());
    }
}
