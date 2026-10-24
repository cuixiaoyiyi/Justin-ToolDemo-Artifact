package cn.ac.ios.justin.generics;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.core.type.TypeRef.Wildcard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bounds the instantiator has to respect: intersections, F-bounds, forward references, nested wildcards, bounded parameters. */
class TypeInstantiatorTest {

    private static TypeVar var(String name, TypeRef... bounds) { return new TypeVar(name, List.of(bounds)); }
    private static ClassRef cls(String name, TypeRef... args) { return new ClassRef(name, List.of(args)); }

    @Test
    void intersectionBoundIsSatisfiedByEveryBound() {
        // <T extends Number & Comparable<T>>
        TypeVar t = var("T", ClassRef.of("java.lang.Number"), cls("java.lang.Comparable", var("T")));
        for (long seed = 0; seed < 20; seed++) {
            TypeRef chosen = new TypeInstantiator(new Random(seed)).bind(List.of(t)).get("T");
            assertTrue(Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Double").contains(chosen.erasureName()), chosen.toSource());
        }
        // <T extends CharSequence & Comparable<T>> leaves only String
        TypeVar s = var("T", ClassRef.of("java.lang.CharSequence"), cls("java.lang.Comparable", var("T")));
        assertEquals("java.lang.String", new TypeInstantiator(new Random(1)).bind(List.of(s)).get("T").erasureName());
    }

    @Test
    void enumBoundGetsAConcreteEnum() {
        // <E extends Enum<E>>: the raw Enum type would be neither instantiable nor within its own bound
        TypeVar e = var("E", cls("java.lang.Enum", var("E")));
        assertEquals("java.util.concurrent.TimeUnit", new TypeInstantiator(new Random(3)).bind(List.of(e)).get("E").erasureName());
    }

    @Test
    void comparableOfAFixedTypeOnlyAdmitsThatType() {
        TypeVar t = var("T", cls("java.lang.Comparable", TypeRef.STRING));
        for (long seed = 0; seed < 10; seed++) {
            assertEquals("java.lang.String", new TypeInstantiator(new Random(seed)).bind(List.of(t)).get("T").erasureName());
        }
    }

    @Test
    void boundsMentioningLaterVariablesAreBoundAfterThem() {
        // <I extends Holder<T>, T>: I's bound must see the T that was actually chosen
        TypeVar i = var("I", cls("demo.Holder", var("T")));
        TypeVar t = var("T");
        Map<String, TypeRef> env = new TypeInstantiator(new Random(5)).bind(List.of(i, t));
        assertEquals(List.of("I", "T"), List.copyOf(env.keySet()), "reported in declaration order");
        ClassRef holder = (ClassRef) env.get("I");
        assertEquals("demo.Holder", holder.binaryName());
        assertEquals(env.get("T"), holder.typeArgs().get(0));
    }

    @Test
    void selfReferentialProjectBoundUsesTheKnowledge() {
        // <T extends BloomFilter<T>> with a project class Simple implements BloomFilter<Simple>
        TypeInstantiator.TypeKnowledge k = new TypeInstantiator.TypeKnowledge() {
            @Override public Optional<TypeRef> satisfying(List<TypeRef> bounds, String selfVar) {
                return bounds.get(0).erasureName().equals("demo.BloomFilter") ? Optional.of(ClassRef.of("demo.Simple")) : Optional.empty();
            }
        };
        TypeVar t = var("T", cls("demo.BloomFilter", var("T")));
        assertEquals("demo.Simple", new TypeInstantiator(new Random(1), k).bind(List.of(t)).get("T").erasureName());
        // without knowledge: the erasure, never a type that mentions T
        TypeRef fallback = new TypeInstantiator(new Random(1)).bind(List.of(t)).get("T");
        assertEquals("demo.BloomFilter", fallback.erasureName());
        assertTrue(TypeSubstitution.freeVars(fallback).isEmpty());
    }

    @Test
    void nestedWildcardsSurviveTopLevelOnesDoNot() {
        TypeInstantiator inst = new TypeInstantiator(new Random(1));
        // List<Class<?>> must stay List<Class<?>>
        TypeRef nested = cls("java.util.List", cls("java.lang.Class", new Wildcard(null, null)));
        assertEquals("java.util.List<java.lang.Class<?>>", inst.concretize(nested, Map.of()).toSource());
        // List<? extends Number> becomes List<Number>
        TypeRef top = cls("java.util.List", new Wildcard(ClassRef.of("java.lang.Number"), null));
        assertEquals("java.util.List<java.lang.Number>", inst.concretize(top, Map.of()).toSource());
    }

    @Test
    void unboundedWildcardRespectsTheParametersBound() {
        // Sorted<K extends Comparable<K>> used as Sorted<?>: Object would not be within K's bound
        TypeInstantiator.TypeKnowledge k = new TypeInstantiator.TypeKnowledge() {
            @Override public List<TypeVar> typeParams(String binaryName) {
                return binaryName.equals("demo.Sorted") ? List.of(var("K", cls("java.lang.Comparable", var("K")))) : List.of();
            }
        };
        TypeRef t = new TypeInstantiator(new Random(2), k).concretize(cls("demo.Sorted", new Wildcard(null, null)), Map.of());
        String arg = ((ClassRef) t).typeArgs().get(0).erasureName();
        assertNotEquals("java.lang.Object", arg);
        assertTrue(Set.of("java.lang.Integer", "java.lang.String").contains(arg), arg);
    }
}
