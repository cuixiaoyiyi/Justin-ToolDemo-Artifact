package cn.ac.ios.justin.core.type;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generic-aware type model shared by every Justin module. Downstream code never
 * touches java.lang.reflect.Type or ASM signatures directly.
 */
public sealed interface TypeRef permits TypeRef.ClassRef, TypeRef.ArrayRef, TypeRef.TypeVar, TypeRef.Wildcard, TypeRef.Primitive {

    /** Binary name of the erasure (java.util.List, int, java.lang.Object for unbounded vars). Arrays use JVM-ish "[" prefix free form: component erasure + "[]". */
    String erasureName();

    /** Java source rendering with fully qualified names, e.g. java.util.List&lt;java.lang.String&gt;. */
    String toSource();

    default boolean isPrimitive() { return this instanceof Primitive p && p.kind() != Kind.VOID; }
    default boolean isVoid() { return this instanceof Primitive p && p.kind() == Kind.VOID; }

    enum Kind {
        BOOLEAN("boolean", "Z", "java.lang.Boolean"),
        BYTE("byte", "B", "java.lang.Byte"),
        CHAR("char", "C", "java.lang.Character"),
        SHORT("short", "S", "java.lang.Short"),
        INT("int", "I", "java.lang.Integer"),
        LONG("long", "J", "java.lang.Long"),
        FLOAT("float", "F", "java.lang.Float"),
        DOUBLE("double", "D", "java.lang.Double"),
        VOID("void", "V", "java.lang.Void");

        public final String keyword;
        public final String descriptor;
        public final String boxed;

        Kind(String keyword, String descriptor, String boxed) {
            this.keyword = keyword;
            this.descriptor = descriptor;
            this.boxed = boxed;
        }

        public static Kind fromDescriptor(char c) {
            for (Kind k : values()) if (k.descriptor.charAt(0) == c) return k;
            throw new IllegalArgumentException("not a primitive descriptor: " + c);
        }

        public static Kind fromKeyword(String s) {
            for (Kind k : values()) if (k.keyword.equals(s)) return k;
            return null;
        }
    }

    record Primitive(Kind kind) implements TypeRef {
        @Override public String erasureName() { return kind.keyword; }
        @Override public String toSource() { return kind.keyword; }
        @Override public String toString() { return toSource(); }
    }

    /** A class or interface type, possibly parameterized. binaryName uses dots for packages and '$' for nested classes. */
    record ClassRef(String binaryName, List<TypeRef> typeArgs) implements TypeRef {
        public ClassRef {
            Objects.requireNonNull(binaryName);
            typeArgs = typeArgs == null ? List.of() : List.copyOf(typeArgs);
        }
        public static ClassRef of(String binaryName) { return new ClassRef(binaryName, List.of()); }
        public static ClassRef of(Class<?> c) { return new ClassRef(c.getName(), List.of()); }
        public boolean isParameterized() { return !typeArgs.isEmpty(); }
        public ClassRef raw() { return typeArgs.isEmpty() ? this : new ClassRef(binaryName, List.of()); }
        @Override public String erasureName() { return binaryName; }
        @Override public String toSource() {
            String base = binaryName.replace('$', '.');
            if (typeArgs.isEmpty()) return base;
            return base + typeArgs.stream().map(TypeRef::toSource).collect(Collectors.joining(", ", "<", ">"));
        }
        @Override public String toString() { return toSource(); }
    }

    record ArrayRef(TypeRef component) implements TypeRef {
        public ArrayRef { Objects.requireNonNull(component); }
        public int dimensions() { return component instanceof ArrayRef a ? 1 + a.dimensions() : 1; }
        public TypeRef elementType() { return component instanceof ArrayRef a ? a.elementType() : component; }
        @Override public String erasureName() { return component.erasureName() + "[]"; }
        @Override public String toSource() { return component.toSource() + "[]"; }
        @Override public String toString() { return toSource(); }
    }

    /** A type variable declared on a class or method. Bounds are empty for an unbounded variable (implicitly Object). */
    record TypeVar(String name, List<TypeRef> bounds) implements TypeRef {
        public TypeVar {
            Objects.requireNonNull(name);
            bounds = bounds == null ? List.of() : List.copyOf(bounds);
        }
        public static TypeVar of(String name) { return new TypeVar(name, List.of()); }
        public TypeRef firstBoundOrObject() { return bounds.isEmpty() ? ClassRef.of("java.lang.Object") : bounds.get(0); }
        @Override public String erasureName() { return firstBoundOrObject().erasureName(); }
        @Override public String toSource() { return name; }
        @Override public String toString() { return name; }
    }

    /** ? / ? extends upper / ? super lower. Exactly one of upper/lower may be non-null; both null means unbounded. */
    record Wildcard(TypeRef upper, TypeRef lower) implements TypeRef {
        public static final Wildcard UNBOUNDED = new Wildcard(null, null);
        public static Wildcard extendsOf(TypeRef upper) { return new Wildcard(upper, null); }
        public static Wildcard superOf(TypeRef lower) { return new Wildcard(null, lower); }
        @Override public String erasureName() { return upper != null ? upper.erasureName() : "java.lang.Object"; }
        @Override public String toSource() {
            if (upper != null) return "? extends " + upper.toSource();
            if (lower != null) return "? super " + lower.toSource();
            return "?";
        }
        @Override public String toString() { return toSource(); }
    }

    // ---- common constants ----
    TypeRef OBJECT = ClassRef.of("java.lang.Object");
    TypeRef STRING = ClassRef.of("java.lang.String");
    TypeRef VOID = new Primitive(Kind.VOID);
    TypeRef INT = new Primitive(Kind.INT);
    TypeRef BOOLEAN = new Primitive(Kind.BOOLEAN);
}
