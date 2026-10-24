package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.EnumValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.Value.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Type-directed random value construction shared by strategies. Every choice comes from the context's
 * Random so results are reproducible. Returns empty when no construction recipe is known.
 */
public final class ValueGenerator {

    private static final String[] WORDS = {"", "a", "abc", "Hello, World", "  spaced  ", "x\ny", "0", "-1", "42", "null", "äöü", "\0"};

    private final GenerationContext ctx;
    private final Random rnd;

    public ValueGenerator(GenerationContext ctx) {
        this.ctx = ctx;
        this.rnd = ctx.random();
    }

    public Optional<Value> generate(TypeRef declared) {
        return generate(ctx.concreteType(declared), 0);
    }

    Optional<Value> generate(TypeRef t, int depth) {
        if (t instanceof Primitive p) return Optional.of(primitive(p.kind()));
        if (t instanceof ArrayRef a) return Optional.of(array(a, depth));
        if (t instanceof ClassRef c) return object(c, depth);
        // TypeVar / Wildcard should have been concretized; fall back to Object
        return object(ClassRef.of("java.lang.Object"), depth);
    }

    // ---------- primitives ----------

    public PrimitiveValue primitive(Kind k) {
        return switch (k) {
            case BOOLEAN -> PrimitiveValue.of(rnd.nextBoolean());
            case BYTE -> PrimitiveValue.of((byte) smallInt());
            case SHORT -> PrimitiveValue.of((short) smallInt());
            case CHAR -> PrimitiveValue.of(randomChar());
            case INT -> PrimitiveValue.of(randomInt());
            case LONG -> PrimitiveValue.of(rnd.nextInt(4) == 0 ? (long) rnd.nextInt() * 1000 : (long) randomInt());
            case FLOAT -> PrimitiveValue.of((float) randomDouble());
            case DOUBLE -> PrimitiveValue.of(randomDouble());
            case VOID -> throw new IllegalArgumentException("void has no values");
        };
    }

    private int smallInt() { return rnd.nextInt(21) - 10; }

    /** Random ints stay within this magnitude; the extremes are the special-value strategy's job. */
    private static final int RANDOM_INT_MAGNITUDE = 1_000_000;

    private int randomInt() {
        // mostly small values (they hit boundaries and array indices), sometimes larger ones; a value
        // used as a size or count must not be able to exhaust the heap on its own
        int r = rnd.nextInt(10);
        if (r < 6) return smallInt();
        if (r < 9) return rnd.nextInt(2001) - 1000;
        return rnd.nextInt(2 * RANDOM_INT_MAGNITUDE + 1) - RANDOM_INT_MAGNITUDE;
    }

    private double randomDouble() {
        int r = rnd.nextInt(10);
        if (r < 6) return Math.round((rnd.nextDouble() * 20 - 10) * 100) / 100.0;
        if (r < 9) return rnd.nextDouble() * 2000 - 1000;
        return rnd.nextGaussian() * 1e6;
    }

    private char randomChar() {
        int r = rnd.nextInt(10);
        if (r < 6) return (char) ('a' + rnd.nextInt(26));
        if (r < 8) return (char) ('0' + rnd.nextInt(10));
        return (char) rnd.nextInt(0x80);
    }

    public StringValue string() {
        if (rnd.nextInt(3) == 0) return new StringValue(WORDS[rnd.nextInt(WORDS.length)]);
        int len = rnd.nextInt(8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(randomChar());
        return new StringValue(sb.toString());
    }

    // ---------- arrays ----------

    private ArrayValue array(ArrayRef a, int depth) { return array(a, depth, -1); }

    /** @param size number of elements, or -1 for a small random length */
    private ArrayValue array(ArrayRef a, int depth, int size) {
        int len = size >= 0 ? size : depth > ctx.config().maxDepth() ? 0 : rnd.nextInt(4);
        List<Value> elems = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            elems.add(generate(a.component(), depth + 1).orElse(new NullValue(a.component())));
        }
        return new ArrayValue(a.component(), elems);
    }

    // ---------- objects ----------

    private Optional<Value> object(ClassRef c, int depth) {
        String n = c.binaryName();
        // 1 in 8 reference values is null: exercises null handling without drowning the pool
        if (depth > 0 && rnd.nextInt(8) == 0) return Optional.of(new NullValue(c));
        switch (n) {
            case "java.lang.String": case "java.lang.CharSequence": return Optional.of(string());
            case "java.lang.Object": return Optional.of(rnd.nextBoolean() ? string() : boxed(Kind.INT));
            case "java.lang.Comparable":
                // Comparable<X>: a value of X itself is the natural choice; raw Comparable falls back to Integer
                if (!c.typeArgs().isEmpty()) {
                    TypeRef x = ctx.concreteType(c.typeArgs().get(0));
                    if (!x.erasureName().equals("java.lang.Comparable") && !x.erasureName().equals("java.lang.Object")) return generate(x, depth);
                }
                return Optional.of(boxed(Kind.INT));
            case "java.lang.Integer": case "java.lang.Number": return Optional.of(boxed(Kind.INT));
            case "java.lang.Long": return Optional.of(boxed(Kind.LONG));
            case "java.lang.Double": return Optional.of(boxed(Kind.DOUBLE));
            case "java.lang.Float": return Optional.of(boxed(Kind.FLOAT));
            case "java.lang.Short": return Optional.of(boxed(Kind.SHORT));
            case "java.lang.Byte": return Optional.of(boxed(Kind.BYTE));
            case "java.lang.Character": return Optional.of(boxed(Kind.CHAR));
            case "java.lang.Boolean": return Optional.of(boxed(Kind.BOOLEAN));
            case "java.lang.StringBuilder": case "java.lang.StringBuffer": case "java.lang.Appendable":
                return Optional.of(new ObjectValue(ClassRef.of(n.equals("java.lang.Appendable") ? "java.lang.StringBuilder" : n),
                        new Recipe.ConstructorCall(n.equals("java.lang.Appendable") ? "java.lang.StringBuilder" : n, "(Ljava/lang/String;)V", List.of(string()))));
            case "java.util.List": case "java.util.Collection": case "java.lang.Iterable": case "java.util.ArrayList": case "java.util.AbstractList":
            case "java.util.AbstractCollection":
                return Optional.of(collection("java.util.ArrayList", "java.util.List", c, depth));
            case "java.util.Set": case "java.util.HashSet": case "java.util.AbstractSet":
                return Optional.of(collection("java.util.HashSet", "java.util.Set", c, depth));
            case "java.util.Map": case "java.util.HashMap": case "java.util.AbstractMap":
                return Optional.of(map(c, depth));
            default: {
                Optional<Value> jdk = jdkValue(c, depth, -1);
                if (jdk.isPresent()) return jdk;
                break;
            }
        }
        switch (n) {
            case "java.util.concurrent.TimeUnit": case "java.lang.Enum": {
                // the JDK enum the type instantiator binds E extends Enum<E> to when the project has no fitting one
                String[] units = {"NANOSECONDS", "MILLISECONDS", "SECONDS", "DAYS"};
                return Optional.of(new EnumValue("java.util.concurrent.TimeUnit", units[rnd.nextInt(units.length)]));
            }
            case "java.util.Optional": {
                Value none = new ObjectValue(c, new Recipe.StaticCall("java.util.Optional", "empty", "()Ljava/util/Optional;", List.of()));
                if (rnd.nextInt(3) == 0 || depth > ctx.config().maxDepth()) return Optional.of(none);
                TypeRef x = c.typeArgs().isEmpty() ? TypeRef.STRING : ctx.concreteType(c.typeArgs().get(0));
                Optional<Value> inner = generate(x, depth + 1).filter(v -> !(v instanceof NullValue));
                return Optional.of(inner.<Value>map(v -> new ObjectValue(c, new Recipe.StaticCall("java.util.Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", List.of(v)))).orElse(none));
            }
            case "java.lang.Class":
                return Optional.of(new Value.ClassValue(c.typeArgs().isEmpty() ? TypeRef.STRING : ctx.concreteType(c.typeArgs().get(0))));
            case "java.util.Locale":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall("java.util.Locale", "forLanguageTag", "(Ljava/lang/String;)Ljava/util/Locale;", List.of(new StringValue(rnd.nextBoolean() ? "en-US" : "zh-CN")))));
            default:
                break;
        }
        List<String> enums = ctx.types().enumConstants(n);
        if (!enums.isEmpty()) return Optional.of(new EnumValue(n, enums.get(rnd.nextInt(enums.size()))));
        Optional<Value> fn = function(c, depth);
        if (fn.isPresent()) return fn;
        return constructed(c, depth, -1);
    }

    /**
     * JDK types that are interfaces or abstract and have an obvious stand-in: views obtained from a list
     * (Iterator, ListIterator, Enumeration), sorted and queue-like collections, map entries, in-memory
     * readers, writers and streams, and a few value classes. Without them every constructor or method that
     * takes one of these types only ever sees null. size &lt; 0 means a random small size.
     */
    private Optional<Value> jdkValue(ClassRef c, int depth, int size) {
        String n = c.binaryName();
        switch (n) {
            case "java.util.Iterator": case "java.util.ListIterator": {
                boolean li = n.equals("java.util.ListIterator");
                ObjectValue list = collection("java.util.ArrayList", "java.util.List", c, depth, size);
                return Optional.of(new ObjectValue(c, new Recipe.InstanceCall(list, "java.util.List", li ? "listIterator" : "iterator",
                        li ? "()Ljava/util/ListIterator;" : "()Ljava/util/Iterator;", List.of())));
            }
            case "java.util.Enumeration":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall("java.util.Collections", "enumeration", "(Ljava/util/Collection;)Ljava/util/Enumeration;",
                        List.of(collection("java.util.ArrayList", "java.util.List", c, depth, size)))));
            case "java.util.SortedSet": case "java.util.NavigableSet": case "java.util.TreeSet":
                return Optional.of(comparableElements(c) ? collection("java.util.TreeSet", "java.util.Set", c, depth, size)
                        : new ObjectValue(new ClassRef("java.util.TreeSet", c.typeArgs().size() == 1 ? List.of(ctx.concreteType(c.typeArgs().get(0))) : List.of()),
                                new Recipe.ConstructorCall("java.util.TreeSet", "()V", List.of())));
            case "java.util.Queue": case "java.util.Deque": case "java.util.LinkedList": case "java.util.AbstractSequentialList": case "java.util.AbstractQueue":
                return Optional.of(collection("java.util.LinkedList", "java.util.List", c, depth, size));
            case "java.util.ArrayDeque":
                return Optional.of(collection("java.util.ArrayDeque", "java.util.List", c, depth, size));
            case "java.util.LinkedHashSet":
                return Optional.of(collection("java.util.LinkedHashSet", "java.util.Set", c, depth, size));
            case "java.util.SortedMap": case "java.util.NavigableMap": case "java.util.TreeMap":
                return Optional.of(map(c, depth, comparableElements(c) ? size : 0, "java.util.TreeMap"));
            case "java.util.LinkedHashMap":
                return Optional.of(map(c, depth, size, "java.util.LinkedHashMap"));
            case "java.util.Map$Entry": case "java.util.AbstractMap$SimpleEntry": {
                TypeRef k = c.typeArgs().size() == 2 ? ctx.concreteType(c.typeArgs().get(0)) : TypeRef.STRING;
                TypeRef v = c.typeArgs().size() == 2 ? ctx.concreteType(c.typeArgs().get(1)) : TypeRef.STRING;
                Optional<Value> kk = generate(k, depth + 1), vv = generate(v, depth + 1);
                if (kk.isEmpty() || vv.isEmpty()) return Optional.empty();
                return Optional.of(new ObjectValue(new ClassRef("java.util.AbstractMap$SimpleEntry", List.of(k, v)),
                        new Recipe.ConstructorCall("java.util.AbstractMap$SimpleEntry", "(Ljava/lang/Object;Ljava/lang/Object;)V", List.of(kk.get(), vv.get()))));
            }
            // in-memory I/O: no file, no network, deterministic content
            case "java.io.Reader": case "java.io.StringReader":
                return Optional.of(stringReader());
            case "java.io.BufferedReader":
                return Optional.of(new ObjectValue(c, new Recipe.ConstructorCall(n, "(Ljava/io/Reader;)V", List.of(stringReader()))));
            case "java.io.InputStream": case "java.io.ByteArrayInputStream": {
                List<Value> bytes = new ArrayList<>();
                for (byte b : string().value().getBytes(java.nio.charset.StandardCharsets.UTF_8)) bytes.add(PrimitiveValue.of(b));
                return Optional.of(new ObjectValue(ClassRef.of("java.io.ByteArrayInputStream"), new Recipe.ConstructorCall("java.io.ByteArrayInputStream", "([B)V",
                        List.of(new ArrayValue(new TypeRef.Primitive(Kind.BYTE), bytes)))));
            }
            case "java.io.Writer": case "java.io.StringWriter":
                return Optional.of(new ObjectValue(ClassRef.of("java.io.StringWriter"), new Recipe.ConstructorCall("java.io.StringWriter", "()V", List.of())));
            case "java.io.PrintWriter":
                return Optional.of(new ObjectValue(c, new Recipe.ConstructorCall(n, "(Ljava/io/Writer;)V",
                        List.of(new ObjectValue(ClassRef.of("java.io.StringWriter"), new Recipe.ConstructorCall("java.io.StringWriter", "()V", List.of()))))));
            case "java.io.OutputStream": case "java.io.ByteArrayOutputStream":
                return Optional.of(byteSink());
            case "java.io.PrintStream":
                return Optional.of(new ObjectValue(c, new Recipe.ConstructorCall(n, "(Ljava/io/OutputStream;)V", List.of(byteSink()))));
            case "java.nio.charset.Charset":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall(n, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;",
                        List.of(new StringValue(rnd.nextInt(4) == 0 ? "ISO-8859-1" : "UTF-8")))));
            case "java.util.regex.Pattern":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall(n, "compile", "(Ljava/lang/String;)Ljava/util/regex/Pattern;",
                        List.of(new StringValue(rnd.nextBoolean() ? "[a-z]+" : "a")))));
            case "java.util.Random":
                return Optional.of(new ObjectValue(c, new Recipe.ConstructorCall(n, "(J)V", List.of(PrimitiveValue.of(42L)))));
            case "java.math.BigInteger":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall(n, "valueOf", "(J)Ljava/math/BigInteger;", List.of(PrimitiveValue.of((long) smallInt())))));
            case "java.math.BigDecimal":
                return Optional.of(new ObjectValue(c, new Recipe.StaticCall(n, "valueOf", "(J)Ljava/math/BigDecimal;", List.of(PrimitiveValue.of((long) smallInt())))));
            default:
                return Optional.empty();
        }
    }

    private ObjectValue stringReader() {
        return new ObjectValue(ClassRef.of("java.io.StringReader"), new Recipe.ConstructorCall("java.io.StringReader", "(Ljava/lang/String;)V", List.of(string())));
    }

    private ObjectValue byteSink() {
        return new ObjectValue(ClassRef.of("java.io.ByteArrayOutputStream"), new Recipe.ConstructorCall("java.io.ByteArrayOutputStream", "()V", List.of()));
    }

    /** Elements (or keys) of a sorted collection have to be mutually comparable: true when the element type is a JDK value type. */
    private boolean comparableElements(ClassRef declared) {
        if (declared.typeArgs().isEmpty()) return false;
        String e = ctx.concreteType(declared.typeArgs().get(0)).erasureName();
        return e.equals("java.lang.String") || e.equals("java.lang.Integer") || e.equals("java.lang.Long") || e.equals("java.lang.Double")
                || e.equals("java.lang.Character") || e.equals("java.lang.Short") || e.equals("java.lang.Byte") || e.equals("java.lang.Float");
    }

    private ObjectValue boxed(Kind k) {
        return new ObjectValue(ClassRef.of(k.boxed), new Recipe.Boxed(primitive(k)));
    }

    private ObjectValue collection(String impl, String factoryIface, ClassRef declared, int depth) { return collection(impl, factoryIface, declared, depth, -1); }

    private ObjectValue collection(String impl, String factoryIface, ClassRef declared, int depth, int size) {
        TypeRef elem = declared.typeArgs().isEmpty() ? TypeRef.OBJECT : ctx.concreteType(declared.typeArgs().get(0));
        List<Value> elems = new ArrayList<>();
        int len = size >= 0 ? size : depth > ctx.config().maxDepth() ? 0 : rnd.nextInt(4);
        boolean distinct = factoryIface.equals("java.util.Set"); // Set.of rejects duplicates
        for (int i = 0, attempts = 0; elems.size() < len && attempts < len * 4 + 4; attempts++) {
            Optional<Value> v = generate(elem, depth + 1);
            if (v.isEmpty() || v.get() instanceof NullValue) continue; // List.of rejects null
            if (distinct && elems.contains(v.get())) continue;
            elems.add(v.get());
        }
        // the declared element type keeps its inner wildcards (List<Class<?>> needs an ArrayList<Class<?>>); values are built for the concrete one
        ClassRef implType = new ClassRef(impl, List.of(declaredArg(declared, 0, elem)));
        return new ObjectValue(implType, new Recipe.ConstructorCall(impl, "(Ljava/util/Collection;)V", List.of(listOf(elem, elems, factoryIface, ctx.config().javaVersion()))));
    }

    /** List.of(...) / Set.of(...) on Java 9+, Arrays.asList(...) (or a HashSet of it) on Java 8. */
    public static Value listOf(TypeRef elem, List<Value> elems, String factoryIface, int javaVersion) {
        if (javaVersion >= 9) {
            return new ObjectValue(new ClassRef(factoryIface, List.of(elem)),
                    new Recipe.StaticCall(factoryIface, "of", "([Ljava/lang/Object;)L" + factoryIface.replace('.', '/') + ";", elems));
        }
        Value asList = new ObjectValue(new ClassRef("java.util.List", List.of(elem)),
                new Recipe.StaticCall("java.util.Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", elems));
        if (factoryIface.equals("java.util.Set")) {
            return new ObjectValue(new ClassRef("java.util.HashSet", List.of(elem)),
                    new Recipe.ConstructorCall("java.util.HashSet", "(Ljava/util/Collection;)V", List.of(asList)));
        }
        return asList;
    }

    private ObjectValue map(ClassRef declared, int depth) { return map(declared, depth, -1); }

    private ObjectValue map(ClassRef declared, int depth, int size) { return map(declared, depth, size, "java.util.HashMap"); }

    private ObjectValue map(ClassRef declared, int depth, int size, String impl) {
        TypeRef k = declared.typeArgs().size() == 2 ? ctx.concreteType(declared.typeArgs().get(0)) : TypeRef.OBJECT;
        TypeRef v = declared.typeArgs().size() == 2 ? ctx.concreteType(declared.typeArgs().get(1)) : TypeRef.OBJECT;
        List<Value> kv = new ArrayList<>();
        int len = size >= 0 ? size : depth > ctx.config().maxDepth() ? 0 : rnd.nextInt(3);
        for (int i = 0; i < len * 2 && kv.size() < len * 2; i++) {
            Optional<Value> kk = generate(k, depth + 1);
            Optional<Value> vv = generate(v, depth + 1);
            if (kk.isPresent() && vv.isPresent() && !(kk.get() instanceof NullValue) && !(vv.get() instanceof NullValue) && !keysOf(kv).contains(kk.get())) {
                kv.add(kk.get());
                kv.add(vv.get());
            }
        }
        ClassRef implType = new ClassRef(impl, List.of(declaredArg(declared, 0, k), declaredArg(declared, 1, v)));
        Recipe empty = new Recipe.ConstructorCall(impl, "()V", List.of());
        if (kv.isEmpty()) return new ObjectValue(implType, empty);
        // a construction sequence (new HashMap, then put ...) works on every Java level; Map.of has no
        // varargs overload, so a single-expression recipe cannot hold an arbitrary number of entries
        List<Value.Call> puts = new ArrayList<>();
        List<Value> seenKeys = new ArrayList<>();
        for (int i = 0; i + 1 < kv.size(); i += 2) {
            if (seenKeys.contains(kv.get(i))) continue;
            seenKeys.add(kv.get(i));
            puts.add(new Value.Call(impl, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", List.of(kv.get(i), kv.get(i + 1))));
        }
        return new ObjectValue(implType, new Recipe.Sequence(empty, puts));
    }

    /** The i-th declared type argument when it is usable in a declaration as it stands (no variables, not itself a wildcard), else the concrete one. */
    private static TypeRef declaredArg(ClassRef declared, int i, TypeRef concrete) {
        if (i >= declared.typeArgs().size()) return concrete;
        TypeRef a = declared.typeArgs().get(i);
        if (a instanceof TypeRef.Wildcard || a instanceof TypeRef.TypeVar || a instanceof Primitive || cn.ac.ios.justin.generics.TypeSubstitution.hasFreeVars(a)) return concrete;
        return a;
    }

    private static List<Value> keysOf(List<Value> kv) {
        List<Value> keys = new ArrayList<>();
        for (int i = 0; i + 1 < kv.size(); i += 2) keys.add(kv.get(i));
        return keys;
    }

    // ---------- containers and arrays at a chosen fill level ----------

    /**
     * A value of the type holding exactly {@code size} elements: an array of that length, a JDK
     * collection or map of that size, or a project object on which {@code size} element-adding calls
     * (add/put/push/offer/append/insert/enqueue, else setters) were made. Empty when the type has no
     * notion of size (size &gt; 0 and nothing to call).
     */
    public Optional<Value> filled(TypeRef declared, int size) {
        TypeRef t = ctx.concreteType(declared);
        if (t instanceof ArrayRef a) return Optional.of(array(a, 0, size));
        if (!(t instanceof ClassRef c)) return Optional.empty();
        switch (c.binaryName()) {
            case "java.util.List": case "java.util.Collection": case "java.lang.Iterable": case "java.util.ArrayList": case "java.util.AbstractList":
                return Optional.of(collection("java.util.ArrayList", "java.util.List", c, 0, size));
            case "java.util.Set": case "java.util.HashSet": case "java.util.AbstractSet":
                return Optional.of(collection("java.util.HashSet", "java.util.Set", c, 0, size));
            case "java.util.Map": case "java.util.HashMap": case "java.util.AbstractMap":
                return Optional.of(map(c, 0, size));
            case "java.util.Iterator": case "java.util.ListIterator": case "java.util.Enumeration": case "java.util.SortedSet": case "java.util.NavigableSet":
            case "java.util.TreeSet": case "java.util.Queue": case "java.util.Deque": case "java.util.LinkedList": case "java.util.ArrayDeque":
            case "java.util.LinkedHashSet": case "java.util.SortedMap": case "java.util.NavigableMap": case "java.util.TreeMap": case "java.util.LinkedHashMap":
                return jdkValue(c, 0, size);
            default:
                if (c.binaryName().startsWith("java.")) return Optional.empty();
                return constructed(c, 0, size);
        }
    }

    // ---------- functional interfaces ----------

    /** method name, arity, and where the result type comes from: a type-argument index, or a fixed type name, or "void". */
    private record Sam(String method, int arity, String result) {}

    private static final Map<String, Sam> JDK_SAMS = Map.ofEntries(
            Map.entry("java.util.function.Function", new Sam("apply", 1, "1")),
            Map.entry("java.util.function.BiFunction", new Sam("apply", 2, "2")),
            Map.entry("java.util.function.Supplier", new Sam("get", 0, "0")),
            Map.entry("java.util.function.Consumer", new Sam("accept", 1, "void")),
            Map.entry("java.util.function.BiConsumer", new Sam("accept", 2, "void")),
            Map.entry("java.util.function.Predicate", new Sam("test", 1, "boolean")),
            Map.entry("java.util.function.BiPredicate", new Sam("test", 2, "boolean")),
            Map.entry("java.util.function.UnaryOperator", new Sam("apply", 1, "0")),
            Map.entry("java.util.function.BinaryOperator", new Sam("apply", 2, "0")),
            Map.entry("java.util.function.ToIntFunction", new Sam("applyAsInt", 1, "int")),
            Map.entry("java.util.function.ToLongFunction", new Sam("applyAsLong", 1, "long")),
            Map.entry("java.util.function.ToDoubleFunction", new Sam("applyAsDouble", 1, "double")),
            Map.entry("java.util.function.IntFunction", new Sam("apply", 1, "0")),
            Map.entry("java.util.function.IntPredicate", new Sam("test", 1, "boolean")),
            Map.entry("java.util.function.IntUnaryOperator", new Sam("applyAsInt", 1, "int")),
            Map.entry("java.util.function.IntBinaryOperator", new Sam("applyAsInt", 2, "int")),
            Map.entry("java.util.function.IntSupplier", new Sam("getAsInt", 0, "int")),
            Map.entry("java.util.function.LongSupplier", new Sam("getAsLong", 0, "long")),
            Map.entry("java.util.function.DoubleSupplier", new Sam("getAsDouble", 0, "double")),
            Map.entry("java.util.function.BooleanSupplier", new Sam("getAsBoolean", 0, "boolean")),
            Map.entry("java.util.function.IntConsumer", new Sam("accept", 1, "void")),
            Map.entry("java.util.Comparator", new Sam("compare", 2, "comparator")),
            Map.entry("java.lang.Runnable", new Sam("run", 0, "void")),
            Map.entry("java.util.concurrent.Callable", new Sam("call", 0, "0")));

    /**
     * An implementation of a single-abstract-method interface for the type arguments in force: JDK
     * functional interfaces, and project interfaces with exactly one abstract method (Transformer,
     * Factory, Predicate, Closure, ...). The result type is the bound one, so a Transformer&lt;Integer,
     * String&gt; really returns a String.
     */
    private Optional<Value> function(ClassRef c, int depth) { return function(c, depth, false); }

    /** @param force do not leave room for known implementations (used when none of them fits the type arguments) */
    private Optional<Value> function(ClassRef c, int depth, boolean force) {
        if (depth > ctx.config().maxDepth() + 1) return Optional.empty();
        Sam sam = JDK_SAMS.get(c.binaryName());
        TypeRef result = null;
        TypeRef firstParam = null;
        if (sam != null) {
            if (sam.result().equals("comparator")) {
                TypeRef x = c.typeArgs().isEmpty() ? TypeRef.OBJECT : c.typeArgs().get(0);
                boolean comparable = switch (x.erasureName()) {
                    case "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Character", "java.lang.Short", "java.lang.Byte", "java.lang.Float" -> true;
                    default -> false;
                };
                if (comparable && rnd.nextBoolean()) {
                    return Optional.of(new ObjectValue(c, new Recipe.StaticCall("java.util.Comparator", "naturalOrder", "()Ljava/util/Comparator;", List.of())));
                }
                // a constant 0 is the only constant that is a lawful comparator
                return Optional.of(new Value.FunctionValue(c, "compare", 2, Value.FunctionValue.Behavior.CONSTANT, PrimitiveValue.of(0)));
            }
            switch (sam.result()) {
                case "void": return Optional.of(new Value.FunctionValue(c, sam.method(), sam.arity(), Value.FunctionValue.Behavior.NOOP, null));
                case "boolean": case "int": case "long": case "double": result = new Primitive(Kind.fromKeyword(sam.result())); break;
                default: {
                    int i = Integer.parseInt(sam.result());
                    result = i < c.typeArgs().size() ? c.typeArgs().get(i) : TypeRef.OBJECT;
                }
            }
            if (sam.arity() >= 1 && !c.typeArgs().isEmpty() && !c.binaryName().startsWith("java.util.function.Int")) firstParam = c.typeArgs().get(0);
        } else {
            if (c.binaryName().startsWith("java.")) return Optional.empty();
            Optional<ClassTarget> oct = ctx.types().lookup(c.binaryName());
            if (oct.isEmpty() || !oct.get().isInterface()) return Optional.empty();
            ClassTarget ct = oct.get();
            List<MethodTarget> abstracts = ct.methods().stream().filter(m -> m.isAbstract() && !m.isStatic())
                    .filter(m -> !(m.name().equals("equals") && m.arity() == 1) && !(m.name().equals("hashCode") && m.arity() == 0) && !(m.name().equals("toString") && m.arity() == 0))
                    .toList();
            if (abstracts.size() != 1) return Optional.empty();
            // abstract methods inherited from super-interfaces would make it non-functional; JDK parents are
            // only accepted when they are known to declare none
            for (TypeRef sup : ct.interfaces()) {
                String sn = sup.erasureName();
                if (sn.equals("java.io.Serializable") || sn.equals("java.lang.Cloneable")) continue;
                Optional<ClassTarget> st = ctx.types().lookup(sn);
                if (st.isEmpty() || st.get().methods().stream().anyMatch(x -> x.isAbstract() && !x.isStatic()) || !st.get().interfaces().isEmpty()) return Optional.empty();
            }
            // a known implementation stays in play one time in three
            if (!force && !ctx.types().knownImplementations(c.binaryName()).isEmpty() && rnd.nextInt(3) == 0) return Optional.empty();
            MethodTarget m = abstracts.get(0);
            Map<String, TypeRef> env = envFor(ct, c);
            env.putAll(bindMissing(m.typeParams(), env));
            sam = new Sam(m.name(), m.arity(), m.returnsVoid() ? "void" : "type");
            if (m.returnsVoid()) return Optional.of(new Value.FunctionValue(c, m.name(), m.arity(), Value.FunctionValue.Behavior.NOOP, null));
            result = ctx.instantiator().concretize(m.returnType(), env);
            if (m.arity() >= 1) firstParam = ctx.instantiator().concretize(m.params().get(0).type(), env);
        }
        // identity when the types allow it, else a constant of the bound result type
        if (firstParam != null && !(result instanceof Primitive) && firstParam.erasureName().equals(result.erasureName()) && rnd.nextBoolean()) {
            return Optional.of(new Value.FunctionValue(c, sam.method(), sam.arity(), Value.FunctionValue.Behavior.IDENTITY, null));
        }
        Optional<Value> constant = generate(result, depth + 2);
        if (constant.isEmpty()) return Optional.empty();
        Value k = constant.get();
        // a lambda body must be one expression: drop a construction sequence's mutators
        if (k instanceof ObjectValue o && o.recipe() instanceof Recipe.Sequence seq) k = new ObjectValue(o.type(), seq.base());
        return Optional.of(new Value.FunctionValue(c, sam.method(), sam.arity(), Value.FunctionValue.Behavior.CONSTANT, k));
    }

    // ---------- project objects ----------

    /**
     * impl as a subtype of the requested parameterized type: searches impl's implements/extends clauses
     * (up the hierarchy) for the requested class and unifies the declared type arguments with the requested
     * ones; empty when they conflict. Parameters of impl that the clause does not determine are bound freshly.
     */
    private Optional<ClassRef> implType(ClassRef requested, ClassTarget impl, int level) {
        if (impl.typeParams().isEmpty()) {
            // a non-generic implementation fits a raw request, or one whose clause matches exactly
            Optional<ClassRef> clause = clauseFor(requested.binaryName(), impl, level);
            if (clause.isPresent() && !requested.typeArgs().isEmpty() && clause.get().typeArgs().size() == requested.typeArgs().size()) {
                for (int i = 0; i < requested.typeArgs().size(); i++) {
                    TypeRef d = clause.get().typeArgs().get(i);
                    if (!(d instanceof TypeRef.TypeVar) && !(d instanceof TypeRef.Wildcard) && !d.erasureName().equals(requested.typeArgs().get(i).erasureName())) return Optional.empty();
                }
            }
            return Optional.of(ClassRef.of(impl.binaryName()));
        }
        Optional<ClassRef> clause = clauseFor(requested.binaryName(), impl, level);
        Map<String, TypeRef> env = new java.util.HashMap<>();
        if (clause.isPresent() && clause.get().typeArgs().size() == requested.typeArgs().size()) {
            for (int i = 0; i < requested.typeArgs().size(); i++) {
                TypeRef d = clause.get().typeArgs().get(i), want = requested.typeArgs().get(i);
                if (d instanceof TypeRef.TypeVar tv) {
                    TypeRef prev = env.putIfAbsent(tv.name(), want);
                    if (prev != null && !prev.equals(want)) return Optional.empty();
                } else if (!(d instanceof TypeRef.Wildcard) && !d.erasureName().equals(want.erasureName())) {
                    return Optional.empty();
                }
            }
        } else if (clause.isEmpty() && impl.typeParams().size() == requested.typeArgs().size()) {
            // clause not found in the scanned hierarchy: same arity is the best guess
            for (int i = 0; i < impl.typeParams().size(); i++) env.put(impl.typeParams().get(i).name(), requested.typeArgs().get(i));
        } else if (clause.isEmpty() && !requested.typeArgs().isEmpty()) {
            return Optional.empty();   // nothing relates impl's parameters to the requested arguments: no guess
        }
        env.putAll(bindMissing(impl.typeParams(), env));
        // the implementation's own bounds must admit the arguments (TreeBidiMap<K extends Comparable<K>> does not fit <Object, Object>)
        for (TypeRef.TypeVar tp : impl.typeParams()) {
            boolean bounded = tp.bounds().stream().anyMatch(b -> !b.erasureName().equals("java.lang.Object"));
            if (bounded && env.getOrDefault(tp.name(), TypeRef.OBJECT).erasureName().equals("java.lang.Object")) return Optional.empty();
        }
        return Optional.of(new ClassRef(impl.binaryName(), impl.typeParams().stream().map(tp -> env.getOrDefault(tp.name(), TypeRef.OBJECT)).toList()));
    }

    /** The implements/extends clause of cls naming the wanted class, looked up directly or one superclass/interface up. */
    private Optional<ClassRef> clauseFor(String wanted, ClassTarget cls, int level) {
        List<TypeRef> parents = new ArrayList<>(cls.interfaces());
        if (cls.superClass() != null) parents.add(cls.superClass());
        for (TypeRef p : parents) if (p instanceof ClassRef pc && pc.binaryName().equals(wanted)) return Optional.of(pc);
        // PatriciaTrie<V> -> AbstractPatriciaTrie<String, V> -> AbstractBitwiseTrie<K, V> -> Trie<K, V>: the clause can be far up
        if (level >= 6) return Optional.empty();
        for (TypeRef p : parents) {
            if (!(p instanceof ClassRef pc)) continue;
            Optional<ClassTarget> pt = ctx.types().lookup(pc.binaryName());
            if (pt.isEmpty()) continue;
            Optional<ClassRef> up = clauseFor(wanted, pt.get(), level + 1);
            if (up.isEmpty()) continue;
            // rewrite the parent's clause in terms of cls's own variables
            Map<String, TypeRef> sub = new java.util.HashMap<>();
            List<TypeRef.TypeVar> ptp = pt.get().typeParams();
            for (int i = 0; i < ptp.size() && i < pc.typeArgs().size(); i++) sub.put(ptp.get(i).name(), pc.typeArgs().get(i));
            return Optional.of(new ClassRef(wanted, up.get().typeArgs().stream().map(a -> a instanceof TypeRef.TypeVar tv && sub.containsKey(tv.name()) ? sub.get(tv.name()) : a).toList()));
        }
        return Optional.empty();
    }

    /** Type-variable environment inside class ct when it is used as c: its parameters bound to c's arguments (or freshly, for a raw use). */
    private Map<String, TypeRef> envFor(ClassTarget ct, ClassRef c) {
        Map<String, TypeRef> env = new java.util.HashMap<>(ctx.typeBindings());
        List<TypeRef.TypeVar> tps = ct.typeParams();
        if (!tps.isEmpty() && tps.size() == c.typeArgs().size()) {
            for (int i = 0; i < tps.size(); i++) env.put(tps.get(i).name(), c.typeArgs().get(i));
        } else if (!tps.isEmpty()) {
            env.putAll(ctx.instantiator().bind(tps));
        }
        return env;
    }

    private Map<String, TypeRef> bindMissing(List<TypeRef.TypeVar> vars, Map<String, TypeRef> env) {
        List<TypeRef.TypeVar> missing = vars.stream().filter(v -> !env.containsKey(v.name())).toList();
        return missing.isEmpty() ? Map.of() : ctx.instantiator().bind(missing);
    }

    private static final List<String> ADDERS = List.of("add", "put", "push", "offer", "append", "insert", "enqueue");
    private static final List<String> SETTERS = List.of("set", "with");

    private static boolean named(MethodTarget m, List<String> prefixes) {
        for (String p : prefixes) if (m.name().startsWith(p)) return true;
        return false;
    }

    /**
     * Builds a project object: a public constructor or a public static factory returning the type,
     * followed by a fill sequence of public mutator calls. One type-variable environment covers the
     * whole construction, so in a {@code LazyList<Integer>} the list, the factory and every added
     * element agree on Integer.
     *
     * @param size number of element-adding calls, or -1 for a small random fill
     */
    private Optional<Value> constructed(ClassRef c, int depth, int size) {
        Optional<ClassTarget> oc = ctx.types().lookup(c.binaryName());
        if (depth > ctx.config().maxDepth()) {
            // at the depth limit: an object that costs nothing to build is still better than null
            if (size <= 0 && oc.isPresent() && !oc.get().isInterface() && !oc.get().isAbstract() && depth <= ctx.config().maxDepth() + 2
                    && oc.get().methods().stream().anyMatch(m -> m.isConstructor() && m.isPublic() && m.arity() == 0)) {
                ClassTarget leaf = oc.get();
                Map<String, TypeRef> lenv = envFor(leaf, c);
                ClassRef self = leaf.typeParams().isEmpty() ? c : new ClassRef(c.binaryName(), leaf.typeParams().stream().map(tp -> lenv.getOrDefault(tp.name(), TypeRef.OBJECT)).toList());
                return Optional.of(new ObjectValue(self, new Recipe.ConstructorCall(c.binaryName(), "()V", List.of())));
            }
            return size > 0 ? Optional.empty() : Optional.of(new NullValue(c));
        }
        if (oc.isEmpty()) return Optional.empty();
        ClassTarget ct = oc.get();
        if (ct.isInterface() || ct.isAbstract()) {
            List<String> impls = ctx.types().knownImplementations(c.binaryName());
            if (!impls.isEmpty()) {
                // an implementation fits when its implements/extends clause can be unified with the requested
                // type arguments (ClosureTransformer<T> implements Transformer<T, T> does not fit Transformer<Integer, String>).
                // Leaf implementations come first: a decorator (CollectionBag needs a Bag) asks for the same kind of
                // value again and ends at the depth limit with null. The order is random within one cost level, and
                // one time in five the costlier ones get their turn first so that decorators still occur as arguments.
                List<String> order = new ArrayList<>(impls);
                java.util.Collections.shuffle(order, rnd);
                Map<String, Integer> cost = new java.util.HashMap<>();
                for (String impl : order) cost.put(impl, constructionCost(impl, c.binaryName()));
                boolean leafFirst = rnd.nextInt(5) != 0 || depth > 0;
                if (leafFirst) order.sort((x, y) -> Integer.compare(cost.get(x), cost.get(y)));
                int tried = 0;
                for (String impl : order) {
                    Optional<ClassRef> typed = ctx.types().lookup(impl).flatMap(it -> implType(c, it, 0));
                    if (typed.isEmpty()) continue;
                    Optional<Value> v = constructed(typed.get(), depth, size);
                    if (v.isPresent() && !(v.get() instanceof NullValue)) return v;
                    if (++tried >= 4) break;
                }
                // no implementation fits these type arguments: a synthesized function if the interface allows one
                return size > 0 ? Optional.empty() : function(c, depth, true);
            }
            // no implementation known: a static factory on the interface/abstract class itself may still work
        }
        Map<String, TypeRef> env = envFor(ct, c);
        ClassRef self = ct.typeParams().isEmpty() ? c : new ClassRef(c.binaryName(), ct.typeParams().stream().map(tp -> env.getOrDefault(tp.name(), TypeRef.OBJECT)).toList());
        List<MethodTarget> ctors = ct.isInterface() || ct.isAbstract() ? List.of() : ct.methods().stream()
                .filter(MethodTarget::isConstructor).filter(MethodTarget::isPublic).filter(m -> !m.isSynthetic())
                .toList();
        List<MethodTarget> factories = ct.methods().stream()
                .filter(m -> m.isStatic() && m.isPublic() && !m.isSynthetic() && !m.isConstructor())
                .filter(m -> m.returnType().erasureName().equals(c.binaryName()))
                .filter(m -> m.params().stream().noneMatch(p -> p.type().erasureName().equals(c.binaryName()))) // not a "copy" method
                .filter(m -> !m.returnType().toSource().contains("?")) // a wildcard in the return type is captured and cannot be declared
                .toList();
        Recipe base = null;
        // constructors first; factories when there is no public constructor or one time in three
        boolean useFactory = !factories.isEmpty() && (ctors.isEmpty() || rnd.nextInt(3) == 0);
        if (useFactory) {
            MethodTarget f = factories.get(rnd.nextInt(factories.size()));
            // a static factory has its own type variables: those in its return type are the requested arguments
            Map<String, TypeRef> fenv = new java.util.HashMap<>();
            if (f.returnType() instanceof ClassRef rc && rc.typeArgs().size() == self.typeArgs().size()) {
                for (int i = 0; i < rc.typeArgs().size(); i++) if (rc.typeArgs().get(i) instanceof TypeRef.TypeVar tv) fenv.put(tv.name(), self.typeArgs().get(i));
            }
            fenv.putAll(bindMissing(f.typeParams(), fenv));
            List<Value> args = disambiguate(f, ct, argsFor(f, depth, fenv));
            if (args != null) base = new Recipe.StaticCall(c.binaryName(), f.name(), f.descriptor(), args);
        }
        if (base == null) {
            if (ctors.isEmpty()) return Optional.empty();
            // prefer fewer parameters: cheaper and more likely to succeed
            List<MethodTarget> sorted = new ArrayList<>(ctors);
            sorted.sort((x, y) -> Integer.compare(x.arity(), y.arity()));
            int pick = Math.min(sorted.size() - 1, rnd.nextInt(Math.min(3, sorted.size())));
            MethodTarget ctor = sorted.get(pick);
            Map<String, TypeRef> cenv = new java.util.HashMap<>(env);
            cenv.putAll(bindMissing(ctor.typeParams(), cenv));
            List<Value> args = disambiguate(ctor, ct, argsFor(ctor, depth, cenv));
            if (args == null) return Optional.empty();
            base = new Recipe.ConstructorCall(c.binaryName(), ctor.descriptor(), args);
        }
        // fill sequence: element-adding methods first, setters otherwise; inherited public methods count
        List<MethodTarget> all = new ArrayList<>();
        ClassTarget cur = ct;
        for (int i = 0; cur != null && i < 6; i++) {
            for (MethodTarget m : cur.methods()) {
                if (!m.isStatic() && m.isPublic() && !m.isSynthetic() && !m.isConstructor() && m.arity() >= 1 && m.arity() <= 2
                        && all.stream().noneMatch(x -> x.name().equals(m.name()) && x.descriptor().equals(m.descriptor()))) all.add(m);
            }
            cur = cur.superClass() == null ? null : ctx.types().lookup(cur.superClass().erasureName()).orElse(null);
        }
        List<MethodTarget> adders = all.stream().filter(m -> named(m, ADDERS)).toList();
        List<MethodTarget> setters = all.stream().filter(m -> named(m, SETTERS)).toList();
        List<MethodTarget> mutators = !adders.isEmpty() ? adders : setters;
        int n;
        if (size >= 0) {
            if (size > 0 && mutators.isEmpty()) return Optional.empty();
            n = adders.isEmpty() ? Math.min(size, setters.size()) : size;
        } else if (mutators.isEmpty() || depth > 1) {
            n = 0;
        } else {
            int r = rnd.nextInt(10);
            n = r < 3 ? 0 : r < 6 ? 1 : 2 + rnd.nextInt(2);
            if (adders.isEmpty()) n = Math.min(n, setters.size());
        }
        List<Value.Call> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MethodTarget mut = adders.isEmpty() ? setters.get(i % setters.size()) : mutators.get(rnd.nextInt(mutators.size()));
            Map<String, TypeRef> menv = new java.util.HashMap<>(env);
            menv.putAll(bindMissing(mut.typeParams(), menv));
            List<Value> args = argsFor(mut, depth, menv);
            // a fill call is there to add state, not to probe null handling: retry once for non-null arguments
            if (args != null && args.stream().anyMatch(a -> a instanceof NullValue)) args = argsFor(mut, depth, menv);
            if (args == null || args.stream().anyMatch(a -> a instanceof NullValue)) continue;
            calls.add(new Value.Call(c.binaryName(), mut.name(), mut.descriptor(), args));
        }
        if (calls.isEmpty()) return size > 0 ? Optional.empty() : Optional.of(new ObjectValue(self, base));
        return Optional.of(new ObjectValue(self, new Recipe.Sequence(base, calls)));
    }

    /**
     * How hard an implementation is to build, for choosing among the implementations of an interface: 0 with a
     * public no-argument constructor, 1 when some public constructor or static factory takes only JDK and
     * primitive types, 2 when every way in needs another project object, 3 when that object is of the requested
     * type itself (a decorator), 4 when nothing public builds it.
     */
    private int constructionCost(String impl, String requested) {
        Optional<ClassTarget> t = ctx.types().lookup(impl);
        if (t.isEmpty() || t.get().isAbstract() || t.get().isInterface()) return 4;
        int best = 4;
        for (MethodTarget m : t.get().methods()) {
            boolean way = m.isPublic() && !m.isSynthetic() && (m.isConstructor() || m.isStatic() && m.returnType().erasureName().equals(impl));
            if (!way) continue;
            int cost = m.arity() == 0 ? 0 : 1;
            for (var prm : m.params()) {
                String pt = prm.type().erasureName();
                if (pt.equals(requested) || pt.equals(impl)) cost = Math.max(cost, 3);
                else if (ctx.types().lookup(pt).isPresent()) cost = Math.max(cost, 2);
            }
            best = Math.min(best, cost);
        }
        return best;
    }

    /** Casts object arguments to the erased parameter types when the class has another overload of the same name and arity. */
    private static List<Value> disambiguate(MethodTarget m, ClassTarget owner, List<Value> args) {
        if (args == null || owner.methods().stream().filter(x -> x.name().equals(m.name()) && x.arity() == m.arity()).count() < 2) return args;
        List<Value> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Value a = args.get(i);
            TypeRef pt = m.params().get(i).type();
            boolean reference = pt instanceof ClassRef;
            if (reference && a instanceof ObjectValue o && !(o.recipe() instanceof Recipe.Boxed) && !a.type().erasureName().equals(pt.erasureName())) {
                out.add(new Value.CastValue(ClassRef.of(pt.erasureName()), a));
            } else {
                out.add(a);
            }
        }
        return out;
    }

    /** Arguments for every parameter of m under the given type-variable environment, or null if one cannot be generated. */
    private List<Value> argsFor(MethodTarget m, int depth, Map<String, TypeRef> env) {
        List<Value> args = new ArrayList<>();
        for (var p : m.params()) {
            Optional<Value> v = generate(ctx.instantiator().concretize(p.type(), env), depth + 1);
            if (v.isEmpty()) return null;
            args.add(v.get());
        }
        return args;
    }
}
