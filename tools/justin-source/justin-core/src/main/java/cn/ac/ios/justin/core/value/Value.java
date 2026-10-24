package cn.ac.ios.justin.core.value;

import cn.ac.ios.justin.core.type.TypeRef;

import java.util.List;
import java.util.Objects;

/**
 * A generated input value, kept symbolic enough to be both executed (justin-exec) and printed as
 * Java source (justin-emit). Object values carry a construction recipe instead of a live object.
 */
public sealed interface Value permits Value.PrimitiveValue, Value.StringValue, Value.NullValue,
        Value.ArrayValue, Value.EnumValue, Value.ClassValue, Value.ObjectValue, Value.VarRef, Value.FunctionValue, Value.CastValue {

    /** Static type this value is meant to be used as. */
    TypeRef type();

    record PrimitiveValue(TypeRef.Kind kind, Object boxed) implements Value {
        public PrimitiveValue { Objects.requireNonNull(boxed); }
        @Override public TypeRef type() { return new TypeRef.Primitive(kind); }
        public static PrimitiveValue of(int v) { return new PrimitiveValue(TypeRef.Kind.INT, v); }
        public static PrimitiveValue of(long v) { return new PrimitiveValue(TypeRef.Kind.LONG, v); }
        public static PrimitiveValue of(double v) { return new PrimitiveValue(TypeRef.Kind.DOUBLE, v); }
        public static PrimitiveValue of(float v) { return new PrimitiveValue(TypeRef.Kind.FLOAT, v); }
        public static PrimitiveValue of(boolean v) { return new PrimitiveValue(TypeRef.Kind.BOOLEAN, v); }
        public static PrimitiveValue of(char v) { return new PrimitiveValue(TypeRef.Kind.CHAR, v); }
        public static PrimitiveValue of(byte v) { return new PrimitiveValue(TypeRef.Kind.BYTE, v); }
        public static PrimitiveValue of(short v) { return new PrimitiveValue(TypeRef.Kind.SHORT, v); }
    }

    record StringValue(String value) implements Value {
        public StringValue { Objects.requireNonNull(value); }
        @Override public TypeRef type() { return TypeRef.STRING; }
    }

    record NullValue(TypeRef type) implements Value {
        public NullValue { Objects.requireNonNull(type); }
    }

    record ArrayValue(TypeRef component, List<Value> elements) implements Value {
        public ArrayValue { elements = List.copyOf(elements); }
        @Override public TypeRef type() { return new TypeRef.ArrayRef(component); }
    }

    record EnumValue(String enumClass, String constant) implements Value {
        @Override public TypeRef type() { return TypeRef.ClassRef.of(enumClass); }
    }

    /** A class literal, e.g. String.class. */
    record ClassValue(TypeRef literal) implements Value {
        @Override public TypeRef type() { return new TypeRef.ClassRef("java.lang.Class", List.of(literal)); }
    }

    /** An object produced by a construction recipe (constructor / static factory / boxing / sequence). */
    record ObjectValue(TypeRef type, Recipe recipe) implements Value {
        public ObjectValue { Objects.requireNonNull(type); Objects.requireNonNull(recipe); }
    }

    /**
     * A reference to a local variable declared earlier in the test. Only appears in emitted statement
     * sequences (after hoisting construction sequences), never in input tuples.
     */
    record VarRef(String name, TypeRef type) implements Value {}

    /**
     * An argument viewed as the (erased) parameter type of the overload it is meant for: needed when the
     * callee has another overload of the same name and arity that the argument's own type also fits
     * (an object implementing both KeyValue and Map.Entry). Transparent at execution time.
     */
    record CastValue(TypeRef to, Value inner) implements Value {
        @Override public TypeRef type() { return to; }
    }

    /**
     * An implementation of a single-abstract-method interface, synthesized for the type arguments the
     * method under test was bound to: a lambda in the emitted test, a dynamic proxy in the sandbox.
     * CONSTANT returns {@code constant} whatever the arguments, IDENTITY returns its first argument,
     * NOOP does nothing (void methods).
     */
    record FunctionValue(TypeRef iface, String methodName, int arity, Behavior behavior, Value constant) implements Value {
        public enum Behavior { CONSTANT, IDENTITY, NOOP }
        @Override public TypeRef type() { return iface; }
    }

    sealed interface Recipe permits Recipe.ConstructorCall, Recipe.StaticCall, Recipe.Boxed, Recipe.Sequence, Recipe.InstanceCall {
        /** new C(args) where C is the declaring class with its descriptor. */
        record ConstructorCall(String className, String descriptor, List<Value> args) implements Recipe {
            public ConstructorCall { args = List.copyOf(args); }
        }
        /** C.m(args) static factory. */
        record StaticCall(String className, String methodName, String descriptor, List<Value> args) implements Recipe {
            public StaticCall { args = List.copyOf(args); }
        }
        /**
         * target.m(args): a value that only exists as the result of a call on another one, such as
         * list.iterator(), list.listIterator(), map.entrySet(). The target is built first, by its own recipe.
         */
        record InstanceCall(Value target, String declaringClass, String methodName, String descriptor, List<Value> args) implements Recipe {
            public InstanceCall { args = List.copyOf(args); }
        }
        /** Boxed primitive (Integer.valueOf etc.), used when the declared type is a wrapper. */
        record Boxed(PrimitiveValue primitive) implements Recipe {}
        /** base, then obj.m1(args); obj.m2(args); ... (setters / mutators) on the constructed object. */
        record Sequence(Recipe base, List<Call> mutators) implements Recipe {
            public Sequence { mutators = List.copyOf(mutators); }
        }
    }

    /** An instance method call applied to an object under construction. */
    record Call(String declaringClass, String methodName, String descriptor, List<Value> args) {
        public Call { args = List.copyOf(args); }
    }
}
