package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.samples.TextRules;
import cn.ac.ios.justin.scan.ClassPathScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The regex channel on TextRules: its inputs, run on the real methods, must reach every outcome. */
class RegexChannelTest {

    private static SootUpLoader loader;
    private static ClassTarget rules;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(TextRules.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        rules = ClassPathScanner.scan(List.of(samples), n -> true).stream().filter(c -> c.simpleName().equals("TextRules")).findFirst().orElseThrow();
        loader = SootUpLoader.minimal(List.of(samples), List.of());
    }

    private static RegexChannel.Result run(String method) {
        MethodTarget m = rules.methods().stream().filter(x -> x.name().equals(method)).findFirst().orElseThrow();
        return new RegexChannel(loader.loadMethod(m).orElseThrow().getBody(), 42,
                sig -> loader.loadMethod(sig).filter(x -> x.hasBody()).map(x -> (sootup.core.model.Body) x.getBody())).generate(Set.of(), true);
    }

    /** Outcomes of f over the solutions; an exception counts as its simple class name. */
    private static Set<Object> outcomes(RegexChannel.Result r, Function<Map<Integer, Object>, Object> f) {
        Set<Object> out = new HashSet<>();
        for (RegexChannel.Solution s : r.solutions()) {
            try {
                out.add(f.apply(s.values()));
            } catch (RuntimeException e) {
                out.add(e.getClass().getSimpleName());
            }
        }
        return out;
    }

    @Test
    void motivatingExampleSplitSubstringEquals() {
        RegexChannel.Result r = run("field");
        Set<Object> seen = outcomes(r, v -> TextRules.field((String) v.get(0)));
        assertTrue(seen.contains("class-field"), seen + " from " + r.solutions());
        assertTrue(seen.stream().anyMatch(o -> o.toString().startsWith("other:")), seen.toString());
        // exception-triggering variants: too few fields, or a third field shorter than 8
        assertTrue(seen.contains("ArrayIndexOutOfBoundsException") || seen.contains("StringIndexOutOfBoundsException"), seen.toString());
        assertTrue(r.patterns().get(0).stream().anyMatch(p -> p.contains("class")), r.patterns().toString());
    }

    @Test
    void nestedPredicatesAreSatisfiedTogether() {
        Set<Object> seen = outcomes(run("route"), v -> TextRules.route((String) v.get(0)));
        assertEquals(Set.of(0, 1, 2, 3), seen);
    }

    @Test
    void trimAndCaseConversionAreLiftedToTheParameter() {
        RegexChannel.Result r = run("isYes");
        List<String> inputs = new ArrayList<>();
        for (RegexChannel.Solution s : r.solutions()) inputs.add((String) s.values().get(0));
        assertTrue(inputs.stream().anyMatch(s -> s != null && TextRules.isYes(s) && s.trim().equalsIgnoreCase("yes")), inputs.toString());
        assertTrue(inputs.stream().anyMatch(s -> s != null && TextRules.isYes(s) && s.contains("!")), inputs.toString());
    }

    @Test
    void builderHistoryBecomesAQuotient() {
        Set<Object> seen = outcomes(run("tag"), v -> TextRules.tag((String) v.get(0)));
        assertTrue(seen.contains("root"), seen.toString());
        assertTrue(seen.contains("long"), seen.toString());
    }

    @Test
    void bufferParameterWithAppend() {
        Set<Object> seen = outcomes(run("suffix"), v -> TextRules.suffix(new StringBuffer((String) v.get(0))));
        assertEquals(Set.of(0, 1, 2), seen);
    }

    @Test
    void indexParameterIsChosenTogetherWithTheString() {
        Set<Object> seen = outcomes(run("pick"), v -> TextRules.pick((String) v.get(0), (Integer) v.get(1)));
        assertTrue(seen.contains('d'), seen.toString());
        assertTrue(seen.contains('a'), seen.toString());
    }

    @Test
    void twoStringParametersAndANumeral() {
        Set<Object> seen = outcomes(run("compare"), v -> TextRules.compare((String) v.get(0), (String) v.get(1)));
        assertTrue(seen.contains(2), seen.toString());
        assertTrue(seen.contains(1), seen.toString());
    }

    @Test
    void oneLoopIterationReachesThePositiveOutcome() {
        RegexChannel.Result r = run("allDigits");
        Set<Object> seen = outcomes(r, v -> TextRules.allDigits((CharSequence) v.get(0)));
        assertEquals(Set.of(true, false), seen, r.solutions().toString());
        assertTrue(r.solutions().stream().anyMatch(s -> s.values().get(0) == null), "the null branch");
    }

    @Test
    void helperCallsAreExpandedIntoTheirPredicates() {
        RegexChannel.Result r = run("label");
        Set<Object> seen = outcomes(r, v -> TextRules.label((String) v.get(0)));
        assertEquals(Set.of("none", "long-tag", "tag", "text"), seen, r.solutions().toString());
    }

    @Test
    void charAtCountedFromTheEnd() {
        Set<Object> seen = outcomes(run("closing"), v -> TextRules.closing((String) v.get(0)));
        assertEquals(Set.of(0, 1, 2), seen);
    }

    @Test
    void targetLinesInsideAWorkerAreReachedThroughTheFacade() {
        MethodTarget m = rules.methods().stream().filter(x -> x.name().equals("cut")).findFirst().orElseThrow();
        sootup.core.model.Body body = loader.loadMethod(m).orElseThrow().getBody();
        // the lines of cutWorker, found through the call in cut
        Set<Integer> lines = new HashSet<>();
        for (sootup.core.jimple.common.stmt.Stmt st : body.getStmts()) {
            if (!(st instanceof sootup.core.jimple.common.stmt.JAssignStmt as) || !(as.getRightOp() instanceof sootup.core.jimple.common.expr.AbstractInvokeExpr inv)) continue;
            sootup.core.model.Body callee = loader.loadMethod(inv.getMethodSignature()).orElseThrow().getBody();
            for (sootup.core.jimple.common.stmt.Stmt cs : callee.getStmts()) lines.add(cs.getPositionInfo().getStmtPosition().getFirstLine());
        }
        assertFalse(lines.isEmpty());
        RegexChannel.Result r = new RegexChannel(body, 42, sig -> loader.loadMethod(sig).filter(x -> x.hasBody()).map(x -> (sootup.core.model.Body) x.getBody()))
                .generate(lines, false);
        Set<Object> seen = outcomes(r, v -> TextRules.cut((String) v.get(0), (String) v.get(1)));
        assertTrue(seen.contains(-1) && seen.contains(0) && seen.contains(1) && seen.contains(2), seen + " from " + r.solutions());
        assertTrue(seen.stream().anyMatch(o -> o instanceof Integer i && i > 100), seen.toString());
    }

    @Test
    void demandsOnOneSplitAreAnsweredTogether() {
        // Listing 1 of the split paper: a guard on the number of segments and predicates on three of them
        RegexChannel.Result r = run("authenticate");
        Set<Object> seen = outcomes(r, v -> TextRules.authenticate((String) v.get(0)));
        assertEquals(Set.of(0, 1, 2), seen, r.solutions().toString());
    }

    @Test
    void keyValueIdiomIsAPartitionAtTheFirstOccurrence() {
        RegexChannel.Result r = run("valueOf");
        Set<Object> seen = new HashSet<>();
        for (RegexChannel.Solution s : r.solutions()) seen.add(String.valueOf(TextRules.valueOf((String) s.values().get(0))));
        assertTrue(seen.containsAll(Set.of("null", "fast-mode", "empty")), seen + " from " + r.solutions());
    }

    @Test
    void regexDelimiterAndNestedSplit() {
        RegexChannel.Result r = run("header");
        Set<Object> seen = outcomes(r, v -> TextRules.header((String) v.get(0)));
        assertEquals(Set.of("second-text", "accept", "other"), seen, r.solutions().toString());
    }

    @Test
    void extensionIdiomIsAPartitionAtTheLastOccurrence() {
        RegexChannel.Result r = run("extension");
        Set<Object> seen = outcomes(r, v -> TextRules.extension((String) v.get(0)));
        assertTrue(seen.containsAll(Set.of("", "tar.gz", "none")), seen + " from " + r.solutions());
        assertTrue(seen.size() >= 4, "some other extension as well: " + seen);
    }

    @Test
    void segmentProfileMaterializesOnlyWhatIsAskedFor() {
        ApiRegexMapping.Partition part = new ApiRegexMapping.Partition(new Term.Param(0, "java.lang.String"), "#", "#", ApiRegexMapping.Partition.Mode.SPLIT, 0);
        List<ApiRegexMapping.Demand> demands = List.of(
                new ApiRegexMapping.Demand(part, ApiRegexMapping.Demand.Access.AT, 2, Rx.lit("x"), null, 0, ApiRegexMapping.Kind.NECESSARY),
                new ApiRegexMapping.Demand(part, ApiRegexMapping.Demand.Access.COUNT, 0, null, ">=", 2, ApiRegexMapping.Kind.NECESSARY));
        String s = SegmentProfile.build(part, demands).sample(new java.util.Random(1), 0);
        String[] pieces = s.split("#");
        assertEquals(3, pieces.length, s);      // depth follows the greatest index, not the weaker count guard
        assertEquals("x", pieces[2]);
        // an exact count below the accessed index cannot be met
        assertNull(SegmentProfile.build(part, List.of(demands.get(0),
                new ApiRegexMapping.Demand(part, ApiRegexMapping.Demand.Access.COUNT, 0, null, "==", 2, ApiRegexMapping.Kind.NECESSARY))));
        // a possibly empty last segment under limit 0 is kept from being dropped
        String t = SegmentProfile.build(part, List.of(new ApiRegexMapping.Demand(part, ApiRegexMapping.Demand.Access.AT, 1, Rx.lit(""), null, 0, ApiRegexMapping.Kind.NECESSARY)))
                .sample(new java.util.Random(1), 0);
        assertEquals("", t.split("#")[1], t);
    }

    @Test
    void predicatesRelatingTwoSegmentsAreRefinedSegmentBySegment() {
        RegexChannel.Result r = run("span");
        Set<Object> seen = outcomes(r, v -> TextRules.span((String) v.get(0)));
        assertTrue(seen.containsAll(Set.of(-1, -2, -3)), seen + " from " + r.solutions());
        assertTrue(seen.stream().anyMatch(o -> o instanceof Integer i && i >= 0), seen + " from " + r.solutions());
    }

    @Test
    void stringSwitchAndMatches() {
        Set<Object> seen = outcomes(run("kind"), v -> TextRules.kind((String) v.get(0)));
        assertTrue(seen.containsAll(Set.of("A", "B", "serial")), seen.toString());
    }

    @Test
    void mergeBoundsTheOpenSegment() {
        // substring(3, 8) over contains("ab"): the result has exactly five characters
        RegexWrapper pre = new RegexWrapper(Rx.len(3, 3), 5, Rx.any(), 3);
        RegexWrapper merged = RegexWrapper.merge(pre, RegexWrapper.terminated(Rx.any().concat(Rx.lit("ab")).concat(Rx.any())));
        assertNotNull(merged);
        String s = merged.r().sample(new java.util.Random(1), 0);
        assertTrue(s.substring(3, 8).contains("ab"), s);
        assertTrue(java.util.regex.Pattern.matches(merged.r().toRegex(), s), merged.r().toRegex());
        // a literal longer than the window cannot be merged
        assertNull(RegexWrapper.merge(pre, RegexWrapper.terminated(Rx.lit("toolong"))));
    }

    @Test
    void quotients() {
        Rx r = Rx.lit("<body>");
        assertEquals("body", r.stripPrefix("<").stripSuffix(">").asLiteral());
        assertNull(r.stripPrefix("["));
        Rx open = Rx.any().concat(Rx.lit("error.log"));
        assertEquals("error", open.stripSuffix(".log").sample(new java.util.Random(1), 0));
    }
}
