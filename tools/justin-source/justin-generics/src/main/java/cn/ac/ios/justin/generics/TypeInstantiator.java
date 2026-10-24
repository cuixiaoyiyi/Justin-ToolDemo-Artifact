package cn.ac.ios.justin.generics;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ArrayRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.core.type.TypeRef.Wildcard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Chooses concrete types for type variables and removes what cannot appear in a declaration.
 *
 * <p>A variable is bound to a type that satisfies <em>all</em> of its bounds: intersection bounds
 * ({@code T extends Number & Comparable<T>}), self-referential bounds ({@code E extends Enum<E>},
 * {@code T extends BloomFilter<T>}) and bounds that mention other variables of the same declaration,
 * whatever their order ({@code <I extends Initializer<T>, T>}). JDK bounds are answered from a small
 * table of well-known types; project bounds through {@link TypeKnowledge}.
 */
public final class TypeInstantiator {

    /** What the instantiator may ask about the subject's classes; the default knows nothing. */
    public interface TypeKnowledge {
        /** Declared type parameters of a class, or an empty list when unknown. */
        default List<TypeVar> typeParams(String binaryName) { return List.of(); }
        /**
         * A concrete type that is a subtype of every bound, where occurrences of {@code selfVar} inside the
         * bounds stand for the type itself (F-bounded polymorphism); empty when none is known.
         */
        default Optional<TypeRef> satisfying(List<TypeRef> bounds, String selfVar) { return Optional.empty(); }
        TypeKnowledge NONE = new TypeKnowledge() {};
    }

    /** A JDK type usable as a type argument, with the erasures of its supertypes. */
    private record Known(TypeRef type, Set<String> supertypes) {}

    private static final List<Known> JDK = List.of(
            new Known(ClassRef.of("java.lang.Integer"), Set.of("java.lang.Object", "java.lang.Number", "java.lang.Comparable", "java.io.Serializable")),
            new Known(TypeRef.STRING, Set.of("java.lang.Object", "java.lang.CharSequence", "java.lang.Comparable", "java.io.Serializable")),
            new Known(ClassRef.of("java.lang.Long"), Set.of("java.lang.Object", "java.lang.Number", "java.lang.Comparable", "java.io.Serializable")),
            new Known(ClassRef.of("java.lang.Double"), Set.of("java.lang.Object", "java.lang.Number", "java.lang.Comparable", "java.io.Serializable")),
            new Known(ClassRef.of("java.util.concurrent.TimeUnit"), Set.of("java.lang.Object", "java.lang.Enum", "java.lang.Comparable", "java.io.Serializable")));

    /** Candidates for an unbounded variable, rotated by the random source. */
    private static final List<TypeRef> UNBOUNDED_CHOICES = List.of(
            ClassRef.of("java.lang.Integer"), TypeRef.STRING, TypeRef.OBJECT);

    /** Collection interfaces as bounds are answered with the implementation the value generator builds. */
    private static final Map<String, String> CONTAINER_IMPLS = Map.of(
            "java.lang.Iterable", "java.util.ArrayList", "java.util.Collection", "java.util.ArrayList", "java.util.List", "java.util.ArrayList",
            "java.util.Set", "java.util.HashSet", "java.util.Map", "java.util.HashMap");

    private final Random random;
    private final TypeKnowledge knowledge;

    public TypeInstantiator(Random random) { this(random, TypeKnowledge.NONE); }

    public TypeInstantiator(Random random, TypeKnowledge knowledge) {
        this.random = random;
        this.knowledge = knowledge == null ? TypeKnowledge.NONE : knowledge;
    }

    /**
     * Builds bindings for the given declared variables. A variable is bound after the other variables
     * its bounds mention, whatever the declaration order; cycles fall back to declaration order.
     */
    public Map<String, TypeRef> bind(List<TypeVar> declared) {
        Map<String, TypeRef> env = new LinkedHashMap<>();
        List<TypeVar> pending = new ArrayList<>(declared);
        Set<String> names = new java.util.HashSet<>();
        for (TypeVar v : declared) names.add(v.name());
        while (!pending.isEmpty()) {
            TypeVar next = null;
            for (TypeVar v : pending) {
                boolean ready = true;
                for (TypeRef b : v.bounds()) {
                    for (String fv : TypeSubstitution.freeVars(b)) {
                        if (!fv.equals(v.name()) && names.contains(fv) && !env.containsKey(fv)) { ready = false; break; }
                    }
                    if (!ready) break;
                }
                if (ready) { next = v; break; }
            }
            if (next == null) next = pending.get(0); // mutually dependent bounds: declaration order
            pending.remove(next);
            TypeRef chosen = choose(next, env);
            // a bound we could not resolve (it still mentions the variable) must not bind the variable to
            // something that mentions itself, or substitution never terminates
            if (TypeSubstitution.freeVars(chosen).contains(next.name())) chosen = ClassRef.of(chosen.erasureName());
            env.put(next.name(), chosen);
        }
        // report in declaration order
        Map<String, TypeRef> ordered = new LinkedHashMap<>();
        for (TypeVar v : declared) ordered.put(v.name(), env.get(v.name()));
        return ordered;
    }

    /** A type for v that satisfies all its bounds under env, as far as the JDK table and the type knowledge can tell. */
    public TypeRef choose(TypeVar v, Map<String, TypeRef> env) {
        List<TypeRef> bounds = new ArrayList<>();
        for (TypeRef b : v.bounds()) {
            TypeRef s = TypeSubstitution.substitute(b, env);
            if (!s.erasureName().equals("java.lang.Object")) bounds.add(s);
        }
        if (bounds.isEmpty()) return UNBOUNDED_CHOICES.get(random.nextInt(UNBOUNDED_CHOICES.size()));
        // JDK types that satisfy every bound, the variable standing for the candidate itself
        List<TypeRef> fits = new ArrayList<>();
        for (Known k : JDK) if (satisfiesAll(k, bounds, v.name())) fits.add(k.type());
        if (!fits.isEmpty()) {
            // the first entries are the ones the value strategies know best; keep some variety
            return fits.get(random.nextInt(Math.min(2, fits.size())));
        }
        if (bounds.size() == 1 && CONTAINER_IMPLS.containsKey(bounds.get(0).erasureName()) && bounds.get(0) instanceof ClassRef bc
                && !TypeSubstitution.freeVars(bc).contains(v.name())) {
            TypeRef concrete = concretize(bc, env, 0);
            if (concrete instanceof ClassRef cc) return new ClassRef(CONTAINER_IMPLS.get(bc.erasureName()), cc.typeArgs());
        }
        Optional<TypeRef> project = knowledge.satisfying(bounds, v.name());
        if (project.isPresent()) return project.get();
        TypeRef first = bounds.get(0);
        if (TypeSubstitution.freeVars(first).contains(v.name())) return ClassRef.of(first.erasureName()); // unresolved self-referential bound
        return concretize(first, env, 0);
    }

    private static boolean satisfiesAll(Known k, List<TypeRef> bounds, String selfVar) {
        for (TypeRef b : bounds) {
            if (!k.supertypes().contains(b.erasureName())) return false;
            if (b instanceof ClassRef bc && !bc.typeArgs().isEmpty()) {
                // Comparable<X> / Enum<X>: X must be the candidate itself (or the variable, or a wildcard admitting it)
                for (TypeRef a : bc.typeArgs()) {
                    if (a instanceof TypeVar tv && tv.name().equals(selfVar)) continue;
                    if (a instanceof Wildcard w) {
                        TypeRef lim = w.lower() != null ? w.lower() : w.upper();
                        if (lim == null || lim instanceof TypeVar || lim.erasureName().equals(k.type().erasureName()) || k.supertypes().contains(lim.erasureName())) continue;
                        return false;
                    }
                    if (!a.erasureName().equals(k.type().erasureName())) return false;
                }
            }
        }
        return true;
    }

    private static final int MAX_DEPTH = 32;

    /**
     * Removes unresolved variables and top-level wildcards from a type so it can be declared and
     * instantiated. Wildcards nested inside a type argument are kept: {@code List<Class<?>>} only
     * accepts a {@code List<Class<?>>}, not a {@code List<Class<Object>>}.
     */
    public TypeRef concretize(TypeRef t, Map<String, TypeRef> env) {
        return concretize(t, env, 0);
    }

    private TypeRef concretize(TypeRef t, Map<String, TypeRef> env, int depth) {
        if (depth > MAX_DEPTH) return ClassRef.of(t.erasureName());
        TypeRef s = TypeSubstitution.substitute(t, env);
        if (s instanceof TypeVar v) {
            TypeRef chosen = choose(v, env);
            return TypeSubstitution.freeVars(chosen).contains(v.name()) ? ClassRef.of(chosen.erasureName()) : concretize(chosen, env, depth + 1);
        }
        if (s instanceof Wildcard w) {
            if (w.upper() != null) return concretize(w.upper(), env, depth + 1);
            if (w.lower() != null) return concretize(w.lower(), env, depth + 1);
            return TypeRef.OBJECT;
        }
        if (s instanceof ArrayRef a) return new ArrayRef(concretize(a.component(), env, depth + 1));
        if (s instanceof ClassRef c && c.isParameterized()) {
            List<TypeVar> params = knowledge.typeParams(c.binaryName());
            List<TypeRef> args = new ArrayList<>();
            Map<String, TypeRef> local = new LinkedHashMap<>(env);
            for (int i = 0; i < c.typeArgs().size(); i++) {
                TypeRef a = c.typeArgs().get(i);
                TypeRef ca;
                if (a instanceof Wildcard w && w.upper() == null && w.lower() == null && i < params.size() && !params.get(i).bounds().isEmpty()) {
                    // Foo<?> where Foo<K extends Comparable<K>>: Object would violate the bound
                    ca = choose(params.get(i), local);
                    if (TypeSubstitution.freeVars(ca).contains(params.get(i).name())) ca = ClassRef.of(ca.erasureName());
                } else if (a instanceof Wildcard || a instanceof TypeVar) {
                    ca = concretize(a, env, depth + 1);
                } else {
                    ca = keepInnerWildcards(a, env, depth + 1);
                }
                // a primitive can never be a type argument
                if (ca instanceof TypeRef.Primitive p) ca = ClassRef.of(p.kind().boxed);
                // an argument that ended up as Object although the parameter is bounded: choose within the bound
                if (i < params.size() && ca.erasureName().equals("java.lang.Object") && params.get(i).bounds().stream().anyMatch(b -> !b.erasureName().equals("java.lang.Object"))) {
                    TypeRef within = choose(params.get(i), local);
                    if (!TypeSubstitution.freeVars(within).contains(params.get(i).name())) ca = within;
                }
                if (i < params.size()) local.put(params.get(i).name(), ca);
                args.add(ca);
            }
            return new ClassRef(c.binaryName(), args);
        }
        return s;
    }

    /** Substitutes variables inside an argument type but leaves its own wildcards alone (generics are invariant). */
    private TypeRef keepInnerWildcards(TypeRef t, Map<String, TypeRef> env, int depth) {
        if (depth > MAX_DEPTH) return ClassRef.of(t.erasureName());
        if (t instanceof TypeVar v) return concretize(v, env, depth + 1);
        if (t instanceof Wildcard w) {
            TypeRef up = w.upper() == null ? null : keepInnerWildcards(w.upper(), env, depth + 1);
            TypeRef low = w.lower() == null ? null : keepInnerWildcards(w.lower(), env, depth + 1);
            return new Wildcard(up, low);
        }
        if (t instanceof ArrayRef a) return new ArrayRef(keepInnerWildcards(a.component(), env, depth + 1));
        if (t instanceof ClassRef c && c.isParameterized()) {
            List<TypeRef> args = new ArrayList<>();
            for (TypeRef a : c.typeArgs()) args.add(keepInnerWildcards(a, env, depth + 1));
            return new ClassRef(c.binaryName(), args);
        }
        return t;
    }
}
