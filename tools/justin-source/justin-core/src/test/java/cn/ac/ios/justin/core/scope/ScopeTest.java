package cn.ac.ios.justin.core.scope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeTest {

    @Test
    void packageScopeRecursiveAndNot() {
        assertTrue(Scope.pkg("a.b", true).coversClass("a.b.c.D"));
        assertFalse(Scope.pkg("a.b", false).coversClass("a.b.c.D"));
        assertTrue(Scope.pkg("a.b", false).coversClass("a.b.D"));
        assertFalse(Scope.pkg("a.b", true).coversClass("a.bc.D"));
    }

    @Test
    void classScopeIncludesNestedClasses() {
        assertTrue(Scope.cls("a.B").coversClass("a.B$C"));
        assertFalse(Scope.cls("a.B").coversClass("a.BC"));
    }

    @Test
    void globFilters() {
        Scope s = Scope.project().withFilters(List.of("a.**"), List.of("a.internal.*"));
        assertTrue(s.passesFilters("a.b.C"));
        assertFalse(s.passesFilters("a.internal.C"));
        assertTrue(s.passesFilters("a.internal.deep.C"));  // "*" does not cross a package boundary
        assertFalse(s.passesFilters("b.C"));
    }

    @Test
    void methodScopeParsesClass() {
        Scope s = Scope.method("a.B#m(int,java.lang.String)");
        assertEquals("a.B", s.className());
        assertTrue(s.coversClass("a.B"));
    }
}
