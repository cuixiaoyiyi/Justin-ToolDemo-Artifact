package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.config.GenerationConfig;
import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.ValuePool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationStrategyTest {

    private static final ClassTarget OWNER = new ClassTarget("a.Svc", 0x0001, List.of(), TypeRef.OBJECT, List.of(), List.of(), List.of(), List.of(), null, null, 61);

    private static MethodTarget method(Parameter... ps) {
        return new MethodTarget("a.Svc", "m", "(x)V", 0x0009, List.of(), List.of(ps), TypeRef.VOID, List.of(), List.of(), 1);
    }

    private static AnnotationInfo ann(String name, Map<String, Object> values) {
        return new AnnotationInfo("jakarta.validation.constraints." + name, values, true);
    }

    private static List<ValuePool> run(MethodTarget m) {
        GenerationContext ctx = new GenerationContext(GenerationConfig.defaults(), OWNER, m, TypeInfoProvider.empty(), Map.of());
        return new AnnotationStrategy().generate(ctx);
    }

    @Test
    void minMaxProduceBoundariesAndViolations() {
        MethodTarget m = method(new Parameter(0, "amount", TypeRef.INT, List.of(ann("Min", Map.of("value", 1L)), ann("Max", Map.of("value", 10L)))));
        ValuePool pool = run(m).get(0);
        List<Object> legal = pool.candidates().stream().filter(Candidate::legal).map(c -> ((Value.PrimitiveValue) c.value()).boxed()).toList();
        List<Object> illegal = pool.candidates().stream().filter(c -> !c.legal()).map(c -> ((Value.PrimitiveValue) c.value()).boxed()).toList();
        assertTrue(legal.containsAll(List.of(1, 2, 10, 9)), legal.toString());
        assertTrue(illegal.containsAll(List.of(0, 11, Integer.MIN_VALUE, Integer.MAX_VALUE)), illegal.toString());
    }

    @Test
    void notNullMarksNullIllegalAndNullableMarksItLegal() {
        MethodTarget m = method(
                new Parameter(0, "a", TypeRef.STRING, List.of(new AnnotationInfo("org.jetbrains.annotations.NotNull", Map.of(), false))),
                new Parameter(1, "b", TypeRef.STRING, List.of(new AnnotationInfo("cn.ac.ios.justin.annotations.Nullable", Map.of(), false))));
        List<ValuePool> pools = run(m);
        assertEquals(1, pools.get(0).size());
        assertFalse(pools.get(0).candidates().get(0).legal());
        assertInstanceOf(Value.NullValue.class, pools.get(0).candidates().get(0).value());
        assertTrue(pools.get(1).candidates().get(0).legal());
    }

    @Test
    void sizeOnStringsAndLists() {
        TypeRef listOfString = new TypeRef.ClassRef("java.util.List", List.of(TypeRef.STRING));
        MethodTarget m = method(
                new Parameter(0, "s", TypeRef.STRING, List.of(ann("Size", Map.of("min", 2, "max", 4)))),
                new Parameter(1, "l", listOfString, List.of(ann("NotEmpty", Map.of()))));
        List<ValuePool> pools = run(m);
        List<String> strings = pools.get(0).candidates().stream().map(c -> ((Value.StringValue) c.value()).value()).toList();
        assertTrue(strings.containsAll(List.of("ab", "abcd", "a", "abcde")), strings.toString());
        assertEquals(3, pools.get(1).size()); // singleton legal, empty illegal, null illegal
    }

    @Test
    void examplesAreParsed() {
        AnnotationInfo ex = new AnnotationInfo("cn.ac.ios.justin.annotations.Examples",
                Map.of("value", List.of("\"a@b.com\""), "invalid", List.of("\"nope\"")), false);
        MethodTarget m = method(new Parameter(0, "e", TypeRef.STRING, List.of(ex)));
        ValuePool pool = run(m).get(0);
        assertEquals(2, pool.size());
        assertEquals("a@b.com", ((Value.StringValue) pool.candidates().get(0).value()).value());
        assertFalse(pool.candidates().get(1).legal());
    }

    @Test
    void unknownAnnotationsAreIgnored() {
        MethodTarget m = method(new Parameter(0, "x", TypeRef.INT, List.of(new AnnotationInfo("java.lang.Deprecated", Map.of(), true))));
        assertTrue(run(m).get(0).isEmpty());
    }
}
