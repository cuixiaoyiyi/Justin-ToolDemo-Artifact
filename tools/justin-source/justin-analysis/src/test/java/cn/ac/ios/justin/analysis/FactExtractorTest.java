package cn.ac.ios.justin.analysis;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Relation;
import cn.ac.ios.justin.scan.ClassPathScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the extractor over the justin-samples classes (a test-scoped dependency). */
class FactExtractorTest {

    private static FactExtractor extractor;
    private static ClassTarget matrix, accountService, registry, order;

    @BeforeAll
    static void load() throws Exception {
        Path samples = Path.of(Class.forName("cn.ac.ios.justin.samples.Matrix").getProtectionDomain().getCodeSource().getLocation().toURI());
        List<ClassTarget> classes = ClassPathScanner.scan(List.of(samples), n -> true);
        matrix = classes.stream().filter(c -> c.simpleName().equals("Matrix")).findFirst().orElseThrow();
        accountService = classes.stream().filter(c -> c.simpleName().equals("AccountService")).findFirst().orElseThrow();
        registry = classes.stream().filter(c -> c.simpleName().equals("Registry")).findFirst().orElseThrow();
        order = classes.stream().filter(c -> c.simpleName().equals("Order")).findFirst().orElseThrow();
        extractor = new FactExtractor(SootUpLoader.minimal(List.of(samples), List.of()));
    }

    private static MethodTarget method(ClassTarget c, String name) {
        return c.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void arrayAccessGivesIndexRelation() {
        MethodFacts f = extractor.extract(method(matrix, "at"));
        assertTrue(f.bodyAvailable());
        assertEquals(1, f.relations().size());
        Relation r = f.relations().get(0);
        assertEquals(Relation.Kind.INDEX_OF, r.kind());
        assertEquals(1, r.dependent());
        assertEquals(0, r.base());
    }

    @Test
    void guardedComparisonsGiveLengthAndRangeRelations() {
        MethodFacts f = extractor.extract(method(matrix, "sumRange"));
        // from < 0 || to > values.length || from > to  ->  to <= length(values); `from` also indexes values via k = from
        assertTrue(f.relations().stream().anyMatch(r -> r.kind() == Relation.Kind.LENGTH_LE && r.dependent() == 2 && r.base() == 0), f.relations().toString());
        assertTrue(f.relations().stream().anyMatch(r -> r.dependent() == 1), f.relations().toString()); // from: RANGE_BELOW of to, or INDEX_OF via k = from
        // a plain guarded pair without other relations yields RANGE with the throwing side as violation
        MethodFacts c = extractor.extract(method(matrix, "clamp"));
        assertTrue(c.relations().stream().anyMatch(r -> (r.kind() == Relation.Kind.RANGE && r.dependent() == 2 && r.base() == 1)
                || (r.kind() == Relation.Kind.RANGE_BELOW && r.dependent() == 1 && r.base() == 2)), c.relations().toString());
        assertTrue(f.explicitThrows().contains("java.lang.IndexOutOfBoundsException"));
        assertTrue(f.intConstants().contains(0));
    }

    @Test
    void listGetAndCharAtGiveIndexRelations() {
        MethodFacts max = extractor.extract(method(matrix, "maxOf"));
        assertTrue(max.relations().stream().anyMatch(r -> r.kind() == Relation.Kind.INDEX_OF && r.dependent() == 1 && r.base() == 0), max.relations().toString());
        MethodFacts ch = extractor.extract(method(matrix, "charAt"));
        assertTrue(ch.relations().stream().anyMatch(r -> r.kind() == Relation.Kind.INDEX_OF && r.dependent() == 1 && r.base() == 0), ch.relations().toString());
    }

    @Test
    void sameLengthDetected() {
        MethodFacts f = extractor.extract(method(matrix, "sameLength"));
        assertTrue(f.relations().stream().anyMatch(r -> r.kind() == Relation.Kind.SAME_LENGTH), f.relations().toString());
    }

    @Test
    void constantsAndThrowsAndPurity() {
        MethodFacts tier = extractor.extract(method(accountService, "tier"));
        assertTrue(tier.intConstants().containsAll(List.of(100, 90, 60, 30)), tier.intConstants().toString());
        assertTrue(tier.explicitThrows().contains("java.lang.IllegalArgumentException"));
        assertTrue(tier.pure(), tier.toString());
        MethodFacts setDefault = extractor.extract(method(registry, "setDefault"));
        assertFalse(setDefault.pure(), setDefault.toString());
        MethodFacts format = extractor.extract(method(registry, "format"));
        assertTrue(format.stringConstants().contains("-"));
        MethodFacts of = extractor.extract(method(order, "of"));
        assertTrue(of.neverReturnsNull(), of.toString());
    }
}
