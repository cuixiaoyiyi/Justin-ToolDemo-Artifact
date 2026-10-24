package cn.ac.ios.justin.core.contract;

import cn.ac.ios.justin.core.contract.Constraints.Spec;
import cn.ac.ios.justin.core.target.AnnotationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unification of third-party and Justin annotations into one spec, and the structural path rules. */
class ConstraintsTest {

    private static AnnotationInfo a(String binary, Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new AnnotationInfo(binary, m, true);
    }

    @Test
    void beanValidationAndHibernateUnifyToBounds() {
        Spec s = Constraints.of(List.of(a("jakarta.validation.constraints.Min", "value", 5L), a("org.hibernate.validator.constraints.Range", "min", 0L, "max", 22L),
                a("jakarta.validation.constraints.NotNull")));
        assertEquals(5L, s.min);
        assertEquals(22L, s.max);
        assertEquals(Boolean.FALSE, s.nullable);
        assertTrue(s.declared);
        assertFalse(s.fromModel);
    }

    @Test
    void exclusiveDecimalBoundsAndDigits() {
        Spec s = Constraints.of(List.of(a("javax.validation.constraints.DecimalMin", "value", "10", "inclusive", false), a("javax.validation.constraints.Digits", "integer", 3L, "fraction", 0L)));
        assertEquals(10L, s.min);
        assertTrue(s.minExclusive);
        assertEquals(999L, s.max);
    }

    @Test
    void stringConstraints() {
        Spec s = Constraints.of(List.of(a("jakarta.validation.constraints.Pattern", "regexp", "[a-z]+"), a("jakarta.validation.constraints.Size", "min", 2L, "max", 8L),
                a("jakarta.validation.constraints.NotBlank"), a("jakarta.validation.constraints.Email")));
        assertEquals(2, s.patterns.size());
        assertEquals("[a-z]+", s.patterns.get(0));
        assertEquals(2, s.sizeMin);
        assertEquals(8, s.sizeMax);
        assertTrue(s.notBlank);
        assertEquals(Boolean.FALSE, s.nullable);
    }

    @Test
    void structuralPathSelectsElementThenSubclassThenField() {
        // JNN Listing 3: @Element(2) @SubClass(Student.class) @Field("age") @Min(5) @Max(22) Person[] ps
        Spec s = Constraints.of(List.of(a("cn.ac.ios.justin.annotations.Element", "value", 2L), a("cn.ac.ios.justin.annotations.SubClass", "value", "Lcn/ac/ios/justin/samples/Enrollment$Student;"),
                a("cn.ac.ios.justin.annotations.Field", "value", "age"), a("cn.ac.ios.justin.annotations.Min", "value", 5L), a("cn.ac.ios.justin.annotations.Max", "value", 22L)));
        assertNull(s.min, "root carries no numeric bound");
        Spec e2 = s.elements.get(2);
        assertNotNull(e2);
        assertEquals("cn.ac.ios.justin.samples.Enrollment$Student", e2.subClass);
        Spec age = e2.fields.get("age");
        assertNotNull(age);
        assertEquals(5L, age.min);
        assertEquals(22L, age.max);
    }

    @Test
    void secondFieldStartsASibling() {
        Spec s = Constraints.of(List.of(a("cn.ac.ios.justin.annotations.Field", "value", "name"), a("cn.ac.ios.justin.annotations.Pattern", "value", "[A-Z]{2}"),
                a("cn.ac.ios.justin.annotations.Field", "value", "age"), a("cn.ac.ios.justin.annotations.Min", "value", 18L)));
        assertEquals(2, s.fields.size());
        assertEquals(List.of("[A-Z]{2}"), s.fields.get("name").patterns);
        assertTrue(s.fields.get("name").fields.isEmpty(), "age is a sibling of name, not nested in it");
        assertEquals(18L, s.fields.get("age").min);
    }

    @Test
    void modelInferredAnnotationsAreMarked() {
        Spec s = Constraints.of(List.of(a("justin.llm.InRange", "min", 1L, "max", 9L)));
        assertTrue(s.fromModel);
        assertFalse(s.declared);
        assertEquals(1L, s.min);
    }

    @Test
    void arrayParaAndUnknownAnnotations() {
        Spec s = Constraints.of(List.of(a("cn.ac.ios.justin.annotations.ArrayPara", "paraIndex", 0L, "flag", -1L), a("org.example.Irrelevant")));
        assertEquals(0, s.arrayParaIndex);
        assertEquals(-1, s.arrayParaFlag);
        assertFalse(s.isEmpty());
        assertTrue(Constraints.of(List.of(a("org.example.Irrelevant"))).isEmpty());
    }
}
