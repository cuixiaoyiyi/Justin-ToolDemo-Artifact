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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Collections as arguments and as state of the receiver (sample class Roster). */
class CollectionSummariesTest {
    private static final String R = "cn.ac.ios.justin.samples.Roster#";

    private static SootUpLoader loader;
    private static ClassTarget roster;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Roster").getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        roster = classes.stream().filter(c -> c.simpleName().equals("Roster")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static MethodTarget method(String name) {
        return roster.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static SymConfig config(boolean collections) {
        return SymConfig.defaults().withObjects(new SymConfig.ObjectModel(8, 0, collections, false, false));
    }

    private static SymResult run(String name, boolean collections, ReceiverStates states, List<String> before) {
        MethodTarget m = method(name);
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        List<SymbolicExecutor.Prefix> calls = new ArrayList<>();
        for (String b : before) calls.add(new SymbolicExecutor.Prefix(method(b), loader.loadMethod(method(b)).orElseThrow().getBody()));
        try (SymbolicExecutor ex = new SymbolicExecutor(config(collections))) {
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

    /** The strings a list input holds, in order; null when the input is not a list built element by element. */
    private static List<String> strings(Value v) {
        if (!(v instanceof Value.ObjectValue o)) return null;
        List<String> out = new ArrayList<>();
        if (o.recipe() instanceof Value.Recipe.ConstructorCall) return out;
        if (!(o.recipe() instanceof Value.Recipe.Sequence s)) return null;
        for (Value.Call c : s.mutators()) {
            Value e = c.args().get(0);
            out.add(e instanceof Value.StringValue sv ? sv.value() : String.valueOf(e));
        }
        return out;
    }

    private static int number(Value v) {
        if (v instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.Boxed b) v = b.primitive();
        return ((Number) ((Value.PrimitiveValue) v).boxed()).intValue();
    }

    @Test
    void withoutAModelACollectionIsAnObject() {
        SymResult r = run("describe", false, null, List.of());
        assertTrue(r.inputs().stream().allMatch(p -> p.args().get(0) == null || p.args().get(0) instanceof Value.NullValue), r.inputs().toString());
    }

    @Test
    void sizeElementsAndMembershipOfAListArgument() {
        SymResult r = run("describe", true, null, List.of());
        boolean none = false, empty = false, many = false, first = false, found = false, some = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value v = p.args().get(0);
            if (v instanceof Value.NullValue) { none = true; continue; }
            List<String> items = strings(v);
            assertNotNull(items, String.valueOf(v));
            if (items.isEmpty()) empty = true;
            else if (items.size() > 3) many = true;
            else if (items.get(0).equals("first")) first = true;
            else if (items.contains("needle")) found = true;
            else some = true;
        }
        assertTrue(none && empty && many && first && found && some,
                "none=" + none + " empty=" + empty + " many=" + many + " first=" + first + " found=" + found + " some=" + some + " " + r.inputs());
    }

    @Test
    void anIterationMeetsTheElements() {
        SymResult r = run("countLong", true, null, List.of());
        int most = 0;
        for (SymResult.PathInput p : r.inputs()) {
            List<String> words = strings(p.args().get(0));
            if (words == null) continue;
            int n = 0;
            for (String w : words) if (w.length() > 5) n++;
            most = Math.max(most, n);
        }
        assertTrue(most >= 2, "two words longer than five characters: " + r.inputs());
    }

    @Test
    void whatAMapHoldsForAKey() {
        SymResult r = run("price", true, null, List.of());
        boolean absent = false, dear = false, cheap = false;
        for (SymResult.PathInput p : r.inputs()) {
            if (!(p.args().get(0) instanceof Value.ObjectValue o) || !(p.args().get(1) instanceof Value.StringValue item)) continue;
            Integer held = null;
            if (o.recipe() instanceof Value.Recipe.Sequence s) {
                for (Value.Call c : s.mutators()) {
                    if (c.args().get(0) instanceof Value.StringValue k && k.value().equals(item.value())) held = number(c.args().get(1));
                }
            }
            if (held == null) absent = true;
            else if (held > 100) dear = true;
            else cheap = true;
        }
        assertTrue(absent && dear && cheap, "absent=" + absent + " dear=" + dear + " cheap=" + cheap + " " + r.inputs());
    }

    @Test
    void membershipInASetOfNumbers() {
        SymResult r = run("both", true, null, List.of());
        boolean both = false;
        for (SymResult.PathInput p : r.inputs()) {
            if (!(p.args().get(0) instanceof Value.ObjectValue o) || !(o.recipe() instanceof Value.Recipe.Sequence s)) continue;
            List<Integer> ids = new ArrayList<>();
            for (Value.Call c : s.mutators()) ids.add(number(c.args().get(0)));
            assertEquals(ids.size(), Set.copyOf(ids).size(), "the elements of a set differ: " + ids);
            if (ids.contains(7) && ids.contains(9)) both = true;
        }
        assertTrue(both, r.inputs().toString());
    }

    @Test
    void aCollectionTheReceiverHoldsTakesItsElements() {
        ReceiverStates states = new ReceiverStates() {
            @Override public int candidates() { return 2; }
            @Override public Object read(int k, List<String> path) {
                String key = String.join("/", path);
                if (key.equals(R + "names")) return new Elements("java.util.ArrayList", k == 0 ? 0 : 2, k == 0 ? List.of() : List.of("ann", "bob"));
                if (key.equals(R + "scores")) return new Elements("java.util.HashMap", 0, List.of());
                return UNKNOWN;
            }
        };
        SymResult last = run("last", true, states, List.of());
        assertTrue(last.inputs().stream().anyMatch(p -> p.receiver() == 0), "the empty roster returns null: " + last.inputs());
        assertTrue(last.inputs().stream().anyMatch(p -> p.receiver() == 1), "the roster of two returns the second name: " + last.inputs());
        // what no candidate fits is the path on which the list is null
        assertTrue(last.stats().receiver().fitted() >= 2, last.stats().toString());
        assertTrue(last.inputs().stream().allMatch(p -> p.receiver() >= 0 || "java.lang.NullPointerException".equals(p.thrown())), last.inputs().toString());
    }

    @Test
    void callsBeforeTheMethodFillTheCollections() {
        ReceiverStates fresh = new ReceiverStates() {
            @Override public int candidates() { return 1; }
            @Override public Object read(int k, List<String> path) {
                String key = String.join("/", path);
                if (key.equals(R + "names")) return new Elements("java.util.ArrayList", 0, List.of());
                if (key.equals(R + "scores")) return new Elements("java.util.HashMap", 0, List.of());
                return UNKNOWN;
            }
        };
        // enroll(a), score(b, n), rank(c): "top" needs a = b = c and n >= 90
        SymResult r = run("rank", true, fresh, List.of("enroll", "score"));
        boolean top = false;
        for (SymResult.PathInput p : r.inputs()) {
            String a = ((Value.StringValue) p.before().get(0).get(0)).value();
            String b = ((Value.StringValue) p.before().get(1).get(0)).value();
            int n = number(p.before().get(1).get(1));
            Value c = p.args().get(0);
            assertEquals(a, b, "score() throws for a name that is not enrolled: " + p);
            if (c instanceof Value.StringValue sv && sv.value().equals(a) && n >= 90) top = true;
        }
        assertTrue(top, "a path on which the score of the enrolled name is 90 or more: " + r.inputs());
    }
}
