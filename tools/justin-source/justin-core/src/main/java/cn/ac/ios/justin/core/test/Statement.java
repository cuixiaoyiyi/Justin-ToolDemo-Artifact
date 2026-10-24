package cn.ac.ios.justin.core.test;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;

import java.util.List;

/**
 * One statement of a generated test. A test is a sequence of these, so a single-call test and a
 * Randoop-style call sequence share the same model and emitter.
 */
public sealed interface Statement permits Statement.Declare, Statement.Invoke, Statement.Assert {

    /** T var = value; */
    record Declare(String var, TypeRef type, Value value) implements Statement {}

    /**
     * [T var =] receiverVar.method(args) or Class.method(args) or new Class(args).
     * resultVar is null when the result is discarded. receiverVar is null for static methods and constructors.
     */
    record Invoke(String resultVar, TypeRef resultType, String receiverVar, MethodTarget method, List<Value> args) implements Statement {
        public Invoke { args = List.copyOf(args); }
    }

    record Assert(Assertion assertion) implements Statement {}
}
