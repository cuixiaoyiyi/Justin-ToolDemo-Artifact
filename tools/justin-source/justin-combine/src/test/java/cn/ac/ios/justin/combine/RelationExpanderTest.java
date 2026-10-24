package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.Relation;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RelationExpanderTest {

    private static final MethodTarget AT = new MethodTarget("a.M", "at", "([II)I", 0x0009, List.of(),
            List.of(new Parameter(0, "row", new TypeRef.ArrayRef(TypeRef.INT), List.of()), new Parameter(1, "i", TypeRef.INT, List.of())),
            TypeRef.INT, List.of(), List.of(), 1);

    @Test
    void indexDerivedFromArrayLength() {
        Value arr = new Value.ArrayValue(TypeRef.INT, List.of(PrimitiveValue.of(5), PrimitiveValue.of(6), PrimitiveValue.of(7), PrimitiveValue.of(8)));
        InputTuple t = new InputTuple(AT, null, List.of(arr, PrimitiveValue.of(99)), Map.of(), "ipog1", Set.of("random"));
        List<InputTuple> out = RelationExpander.expand(AT, List.of(t), List.of(new Relation(Relation.Kind.INDEX_OF, 1, 0, "array-access")), List.of(), 3, 100);
        List<Object> legal = out.stream().filter(x -> x.source().startsWith("relation:")).map(x -> ((PrimitiveValue) x.args().get(1)).boxed()).toList();
        List<Object> illegal = out.stream().filter(x -> x.source().startsWith("illegal:")).map(x -> ((PrimitiveValue) x.args().get(1)).boxed()).toList();
        assertEquals(List.of(0, 2, 3), legal);
        assertEquals(List.of(-1, 4), illegal);
        assertTrue(out.stream().allMatch(x -> x.args().get(0) == arr));
    }

    @Test
    void emptyBaseGivesOnlyViolations() {
        Value arr = new Value.ArrayValue(TypeRef.INT, List.of());
        InputTuple t = new InputTuple(AT, null, List.of(arr, PrimitiveValue.of(0)), Map.of(), "ipog1", Set.of("random"));
        List<InputTuple> out = RelationExpander.expand(AT, List.of(t), List.of(new Relation(Relation.Kind.INDEX_OF, 1, 0, "array-access")), List.of(), 3, 100);
        assertTrue(out.stream().noneMatch(x -> x.source().startsWith("relation:")));
        // -1 is a violation; 0 (the length) coincides with the placeholder row already emitted and is deduplicated
        assertTrue(out.stream().anyMatch(x -> x.source().startsWith("illegal:") && ((PrimitiveValue) x.args().get(1)).boxed().equals(-1)));
    }

    @Test
    void lengthOfReadsRecipes() {
        Value list = new Value.ObjectValue(new TypeRef.ClassRef("java.util.ArrayList", List.of(TypeRef.STRING)),
                new Value.Recipe.ConstructorCall("java.util.ArrayList", "(Ljava/util/Collection;)V", List.of(
                        new Value.ObjectValue(TypeRef.ClassRef.of("java.util.List"),
                                new Value.Recipe.StaticCall("java.util.Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;",
                                        List.of(new Value.StringValue("a"), new Value.StringValue("b")))))));
        assertEquals(2, RelationExpander.lengthOf(list).getAsInt());
        assertEquals(3, RelationExpander.lengthOf(new Value.StringValue("abc")).getAsInt());
        assertTrue(RelationExpander.lengthOf(PrimitiveValue.of(1)).isEmpty());
    }
}
