package cn.ac.ios.justin.generics;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SignatureParserTest {

    @Test
    void parsesMethodDescriptor() {
        var sig = SignatureParser.parseMethodDescriptor("(I[Ljava/lang/String;J)V");
        assertEquals(List.of("int", "java.lang.String[]", "long"), sig.params().stream().map(TypeRef::toSource).toList());
        assertTrue(sig.returnType().isVoid());
    }

    @Test
    void parsesGenericMethodSignature() {
        var sig = SignatureParser.parseMethodSignature(
                "<T::Ljava/lang/Comparable<-TT;>;>(Ljava/util/List<+TT;>;)TT;",
                "(Ljava/util/List;)Ljava/lang/Comparable;");
        assertEquals(1, sig.typeParams().size());
        TypeVar t = sig.typeParams().get(0);
        assertEquals("T", t.name());
        assertEquals("java.lang.Comparable<? super T>", t.bounds().get(0).toSource());
        assertEquals("java.util.List<? extends T>", sig.params().get(0).toSource());
        assertEquals("T", sig.returnType().toSource());
        assertEquals("java.lang.Comparable", sig.returnType().erasureName());
    }

    @Test
    void parsesClassSignatureWithNestedType() {
        var sig = SignatureParser.parseClassSignature(
                "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/util/AbstractMap<TK;TV;>;Ljava/util/Map$Entry<TK;TV;>;",
                "java/util/AbstractMap", new String[]{"java/util/Map$Entry"});
        assertEquals(List.of("K", "V"), sig.typeParams().stream().map(TypeVar::name).toList());
        assertEquals("java.util.AbstractMap<K, V>", sig.superClass().toSource());
        assertEquals("java.util.Map.Entry<K, V>", sig.interfaces().get(0).toSource());
    }

    @Test
    void innerClassSignatureUsesDollar() {
        TypeRef t = SignatureParser.parseType("Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;");
        assertEquals("java.util.Map$Entry", t.erasureName());
    }

    @Test
    void substitutesAndConcretizes() {
        TypeRef list = SignatureParser.parseType("Ljava/util/List<+TT;>;");
        TypeRef bound = TypeSubstitution.substitute(list, Map.of("T", ClassRef.of("java.lang.Integer")));
        assertEquals("java.util.List<? extends java.lang.Integer>", bound.toSource());
        TypeRef concrete = new TypeInstantiator(new Random(1)).concretize(bound, Map.of());
        assertEquals("java.util.List<java.lang.Integer>", concrete.toSource());
        assertFalse(TypeSubstitution.hasFreeVars(concrete));
    }

    @Test
    void recursiveBoundBindsToASelfComparableType() {
        var sig = SignatureParser.parseMethodSignature("<T::Ljava/lang/Comparable<TT;>;>(TT;TT;)TT;", "(Ljava/lang/Comparable;Ljava/lang/Comparable;)Ljava/lang/Comparable;");
        Map<String, TypeRef> env = new TypeInstantiator(new Random(1)).bind(sig.typeParams());
        // any type that is Comparable to itself will do
        assertTrue(java.util.Set.of("java.lang.Integer", "java.lang.String").contains(env.get("T").toSource()), env.get("T").toSource());
    }

    @Test
    void recursiveEnumBoundTerminates() {
        var sig = SignatureParser.parseMethodSignature("<E:Ljava/lang/Enum<TE;>;>(Ljava/lang/Class<TE;>;)Ljava/util/List<TE;>;",
                "(Ljava/lang/Class;)Ljava/util/List;");
        TypeInstantiator inst = new TypeInstantiator(new Random(1));
        Map<String, TypeRef> env = inst.bind(sig.typeParams());
        // a concrete enum: the raw Enum type is neither instantiable nor within its own bound
        assertEquals("java.util.concurrent.TimeUnit", env.get("E").toSource());
        assertEquals("java.util.List<java.util.concurrent.TimeUnit>", inst.concretize(sig.returnType(), env).toSource());
    }

    @Test
    void signatureArityMismatchFallsBackToDescriptor() {
        // enum constructor: descriptor has the synthetic (String, int) prefix, signature omits them
        var sig = SignatureParser.parseMethodSignature("(Ljava/util/List<Ljava/lang/String;>;)V", "(Ljava/lang/String;ILjava/util/List;)V");
        assertEquals(3, sig.params().size());
        assertEquals("java.util.List<java.lang.String>", sig.params().get(2).toSource());
    }
}
