package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.strategies.TypeInfoProvider;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.core.JavaSootClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The classes of the project and its dependencies as the loader sees them, and those of the JDK by reflection.
 * Answers are kept: the same questions come back for every method of a class.
 */
final class Hierarchy implements TypeOracle {
    private static final List<String> UNKNOWN = List.of("?");

    private final SootUpLoader loader;
    private final TypeInfoProvider types;
    /** Direct supertypes by class; UNKNOWN for a class nobody knows. */
    private final Map<String, List<String>> parents;
    private final Map<String, Optional<Boolean>> subtypes = new ConcurrentHashMap<>();

    Hierarchy(SootUpLoader loader, TypeInfoProvider types, Map<String, List<String>> parents) {
        this.loader = loader;
        this.types = types;
        this.parents = parents;
    }

    private Optional<JavaSootClass> load(String name) {
        try {
            return loader.loadClass(name);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private List<String> parentsOf(String cls) {
        return parents.computeIfAbsent(cls, c -> {
            Optional<JavaSootClass> sc = load(c);
            if (sc.isPresent()) {
                List<String> out = new ArrayList<>();
                sc.get().getSuperclass().ifPresent(t -> out.add(t.toString()));
                for (ClassType t : sc.get().getInterfaces()) out.add(t.toString());
                return out;
            }
            Class<?> j = TypeOracle.load(c);
            if (j == null) return UNKNOWN;
            List<String> out = new ArrayList<>();
            if (j.getSuperclass() != null) out.add(j.getSuperclass().getName());
            for (Class<?> i : j.getInterfaces()) out.add(i.getName());
            return out;
        });
    }

    @Override
    public Boolean subtype(String sub, String sup) {
        if (sub.equals(sup) || sup.equals("java.lang.Object")) return true;
        if (sub.endsWith("[]") || sup.endsWith("[]")) return null;
        return subtypes.computeIfAbsent(sub + "<" + sup, k -> {
            // the supertypes of sub, as far as they are known
            Set<String> seen = new HashSet<>();
            List<String> todo = new ArrayList<>(List.of(sub));
            boolean complete = true;
            while (!todo.isEmpty()) {
                String c = todo.remove(todo.size() - 1);
                if (!seen.add(c)) continue;
                if (c.equals(sup)) return Optional.of(true);
                List<String> ps = parentsOf(c);
                if (ps == UNKNOWN) { complete = false; continue; }
                todo.addAll(ps);
            }
            // "not a subtype" needs the whole chain, and a supertype that exists
            if (!complete || parentsOf(sup) == UNKNOWN) return Optional.empty();
            return Optional.of(false);
        }).orElse(null);
    }

    @Override
    public boolean isInterface(String type) {
        Optional<JavaSootClass> sc = load(type);
        if (sc.isPresent()) return sc.get().isInterface();
        Class<?> j = TypeOracle.load(type);
        return j != null && j.isInterface();
    }

    @Override
    public boolean instantiable(String type) {
        Optional<JavaSootClass> sc = load(type);
        if (sc.isPresent()) return sc.get().isConcrete();
        return TypeOracle.JDK.instantiable(type);
    }

    @Override
    public List<String> implementations(String type) {
        try {
            return types.knownImplementations(type);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public Optional<Body> body(String exactClass, MethodSignature declared) {
        String cls = exactClass;
        for (int up = 0; cls != null && up < 12; up++) {
            Optional<JavaSootClass> sc = load(cls);
            if (sc.isEmpty()) return Optional.empty();
            for (SootMethod m : sc.get().getMethods()) {
                if (!m.getSignature().getSubSignature().equals(declared.getSubSignature())) continue;
                if (m.isAbstract() || m.isNative() || !m.hasBody()) return Optional.empty();
                try {
                    return Optional.of(m.getBody());
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }
            cls = sc.get().getSuperclass().map(Object::toString).orElse(null);
        }
        return Optional.empty();
    }
}
