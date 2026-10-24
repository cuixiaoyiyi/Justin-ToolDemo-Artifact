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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the executor over justin-samples methods and checks the inputs reach the interesting branches. */
class SymbolicExecutorTest {

    private static SootUpLoader loader;
    private static ClassTarget accountService, matrix;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Matrix").getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        accountService = classes.stream().filter(c -> c.simpleName().equals("AccountService")).findFirst().orElseThrow();
        matrix = classes.stream().filter(c -> c.simpleName().equals("Matrix")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static MethodTarget method(ClassTarget c, String name) {
        return c.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static SymResult run(MethodTarget m) {
        Body body = loader.loadMethod(m).orElseThrow().getBody();
        try (SymbolicExecutor ex = new SymbolicExecutor(SymConfig.defaults())) {
            return ex.run(m, body, Set.of());
        }
    }

    @Test
    void tierReachesEveryBand() {
        // tier(score): <0 or >100 throws; >=90 -> 3; >=60 -> 2; >=30 -> 1; else 0
        SymResult r = run(method(accountService, "tier"));
        Set<Integer> scores = new HashSet<>();
        int thrown = 0;
        for (SymResult.PathInput p : r.inputs()) {
            int score = (Integer) ((PrimitiveValue) p.args().get(0)).boxed();
            scores.add(score);
            if (p.thrown() != null) thrown++;
        }
        assertTrue(scores.stream().anyMatch(v -> v >= 90 && v <= 100), scores.toString());
        assertTrue(scores.stream().anyMatch(v -> v >= 60 && v < 90), scores.toString());
        assertTrue(scores.stream().anyMatch(v -> v >= 30 && v < 60), scores.toString());
        assertTrue(scores.stream().anyMatch(v -> v >= 0 && v < 30), scores.toString());
        assertTrue(thrown >= 1, "an out-of-range path should throw");
        assertTrue(r.stats().pathsSatisfiable() >= 5);
    }

    @Test
    void clampFindsBothOrdersAndAllThreeOutcomes() {
        SymResult r = run(method(matrix, "clamp"));
        boolean below = false, above = false, inside = false, thrown = false;
        for (SymResult.PathInput p : r.inputs()) {
            int value = (Integer) ((PrimitiveValue) p.args().get(0)).boxed();
            int lo = (Integer) ((PrimitiveValue) p.args().get(1)).boxed();
            int hi = (Integer) ((PrimitiveValue) p.args().get(2)).boxed();
            if (p.thrown() != null) { thrown = true; assertTrue(lo > hi); continue; }
            assertTrue(lo <= hi);
            if (value < lo) below = true; else if (value > hi) above = true; else inside = true;
        }
        assertTrue(below && above && inside && thrown, "below=" + below + " above=" + above + " inside=" + inside + " thrown=" + thrown);
    }

    @Test
    void arrayIndexPathsIncludeInBoundsAndOutOfBounds() {
        SymResult r = run(method(matrix, "at"));
        boolean inBounds = false, oob = false, npe = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value arr = p.args().get(0);
            if (arr instanceof Value.NullValue) { npe = true; continue; }
            int len = ((Value.ArrayValue) arr).elements().size();
            int i = (Integer) ((PrimitiveValue) p.args().get(1)).boxed();
            if (p.thrown() != null && p.thrown().contains("ArrayIndexOutOfBounds")) { oob = true; assertTrue(i < 0 || i >= len); }
            else if (p.thrown() == null) { inBounds = true; assertTrue(i >= 0 && i < len); }
        }
        assertTrue(inBounds && oob, "inBounds=" + inBounds + " oob=" + oob + " npe=" + npe);
    }

    @Test
    void stringConstraintsAreSolved() {
        // validEmail(email): null -> false; matches regex -> true (regex itself unmodeled: fresh symbol)
        // open(account, initial): blank / length checks on the string, negative check on the long
        SymResult r = run(method(accountService, "open"));
        boolean shortName = false, longName = false, okName = false, negative = false;
        for (SymResult.PathInput p : r.inputs()) {
            Value a = p.args().get(0);
            long initial = (Long) ((PrimitiveValue) p.args().get(1)).boxed();
            if (a instanceof Value.StringValue s) {
                if (s.value().length() < 3 && !s.value().trim().isEmpty()) shortName = true;
                if (s.value().length() > 16) longName = true;
                if (s.value().length() >= 3 && s.value().length() <= 16 && !s.value().trim().isEmpty() && initial >= 0 && p.thrown() == null) okName = true;
            }
            if (initial < 0) negative = true;
        }
        assertTrue(longName, "a name longer than 16 should be found");
        assertTrue(negative, "a negative initial amount should be found");
        assertTrue(okName || shortName, "some non-throwing or short-name path expected");
    }
}
