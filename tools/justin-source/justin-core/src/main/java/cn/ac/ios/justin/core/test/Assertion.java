package cn.ac.ios.justin.core.test;

import cn.ac.ios.justin.core.value.Value;

/** Assertions the oracle can produce. Each renders to one JUnit 5 call. */
public sealed interface Assertion permits Assertion.Equals, Assertion.ArrayEquals, Assertion.IsNull,
        Assertion.NotNull, Assertion.IsTrue, Assertion.IsFalse, Assertion.Throws, Assertion.Condition {

    /** Kind tag for reports. */
    String kind();

    /** assertEquals(expected, actualVar) with delta for floating point. */
    record Equals(Value expected, String actualVar, Double delta) implements Assertion {
        @Override public String kind() { return "equals"; }
    }

    /** castTo, when non-null, is the array type the actual variable must be cast to (it was declared as Object). */
    record ArrayEquals(Value expected, String actualVar, cn.ac.ios.justin.core.type.TypeRef castTo) implements Assertion {
        @Override public String kind() { return "arrayEquals"; }
    }

    record IsNull(String actualVar) implements Assertion {
        @Override public String kind() { return "null"; }
    }

    record NotNull(String actualVar) implements Assertion {
        @Override public String kind() { return "notNull"; }
    }

    record IsTrue(String actualVar) implements Assertion {
        @Override public String kind() { return "true"; }
    }

    record IsFalse(String actualVar) implements Assertion {
        @Override public String kind() { return "false"; }
    }

    /** assertThrows(Exception.class, () -> invoke). The invoke statement is rendered inside the lambda. */
    /** assertTrue over a Java boolean expression (a contract derived from annotations); message names the contract. */
    record Condition(String expression, String message) implements Assertion {
        @Override public String kind() { return "condition"; }
    }
    record Throws(String exceptionClass, Statement.Invoke invoke) implements Assertion {
        @Override public String kind() { return "throws"; }
    }
}
