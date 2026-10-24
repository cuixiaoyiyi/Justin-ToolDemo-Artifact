package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.TypeVar;
import cn.ac.ios.justin.generics.TypeInstantiator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Answers the type instantiator's questions from the scanned classes: the declared parameters of a
 * class (so {@code Foo<?>} can respect Foo's bounds), and a concrete project class that satisfies a
 * set of bounds, including F-bounds such as {@code T extends BloomFilter<T>} (a class whose
 * implements clause names itself as the argument) and intersections with JDK interfaces.
 */
public final class ProjectTypeKnowledge implements TypeInstantiator.TypeKnowledge {
    private final TypeInfoProvider types;

    public ProjectTypeKnowledge(TypeInfoProvider types) { this.types = types; }

    @Override public List<TypeVar> typeParams(String binaryName) {
        return types.lookup(binaryName).map(ClassTarget::typeParams).orElse(List.of());
    }

    @Override public Optional<TypeRef> satisfying(List<TypeRef> bounds, String selfVar) {
        // candidates come from the first bound that is a project type; the others are checked against them
        TreeSet<String> candidates = new TreeSet<>();
        for (TypeRef b : bounds) {
            Optional<ClassTarget> bt = types.lookup(b.erasureName());
            if (bt.isEmpty()) continue;
            candidates.addAll(types.knownImplementations(b.erasureName()));
            if (!bt.get().isInterface() && !bt.get().isAbstract()) candidates.add(b.erasureName());
            break;
        }
        for (String name : candidates) {
            Optional<ClassTarget> oc = types.lookup(name);
            if (oc.isEmpty()) continue;
            ClassTarget c = oc.get();
            if (!c.isPublic() || c.isInterface() || c.isAbstract() || c.isAnonymous() || !c.typeParams().isEmpty()) continue;
            boolean ok = true;
            for (TypeRef b : bounds) {
                if (!conforms(c, b, selfVar)) { ok = false; break; }
            }
            if (ok) return Optional.of(ClassRef.of(name));
        }
        return Optional.empty();
    }

    /** c is a subtype of the bound, and where the bound's arguments mention selfVar the clause names c itself. */
    private boolean conforms(ClassTarget c, TypeRef bound, String selfVar) {
        if (bound.erasureName().equals(c.binaryName())) return true;
        Optional<ClassRef> clause = clause(c, bound.erasureName(), 0);
        if (clause.isEmpty()) return false;
        if (!(bound instanceof ClassRef bc) || bc.typeArgs().isEmpty() || clause.get().typeArgs().size() != bc.typeArgs().size()) return true;
        for (int i = 0; i < bc.typeArgs().size(); i++) {
            TypeRef want = bc.typeArgs().get(i), have = clause.get().typeArgs().get(i);
            if (want instanceof TypeVar tv && tv.name().equals(selfVar)) {
                if (!have.erasureName().equals(c.binaryName())) return false;
            } else if (!(want instanceof TypeRef.Wildcard) && !(want instanceof TypeVar) && !(have instanceof TypeVar)
                    && !want.erasureName().equals(have.erasureName())) {
                return false;
            }
        }
        return true;
    }

    /** The extends/implements clause of c (searched up to three levels) that names the wanted type. */
    private Optional<ClassRef> clause(ClassTarget c, String wanted, int level) {
        List<TypeRef> parents = new ArrayList<>(c.interfaces());
        if (c.superClass() != null) parents.add(c.superClass());
        for (TypeRef p : parents) if (p instanceof ClassRef pc && pc.binaryName().equals(wanted)) return Optional.of(pc);
        if (level >= 3) return Optional.empty();
        for (TypeRef p : parents) {
            if (!(p instanceof ClassRef pc)) continue;
            Optional<ClassTarget> pt = types.lookup(pc.binaryName());
            if (pt.isEmpty()) continue;
            Optional<ClassRef> up = clause(pt.get(), wanted, level + 1);
            if (up.isEmpty()) continue;
            // express the parent's clause with c's own arguments
            List<TypeVar> ptp = pt.get().typeParams();
            List<TypeRef> args = new ArrayList<>();
            for (TypeRef a : up.get().typeArgs()) {
                TypeRef r = a;
                if (a instanceof TypeVar tv) {
                    for (int i = 0; i < ptp.size() && i < pc.typeArgs().size(); i++) if (ptp.get(i).name().equals(tv.name())) r = pc.typeArgs().get(i);
                }
                args.add(r);
            }
            return Optional.of(new ClassRef(wanted, args));
        }
        return Optional.empty();
    }
}
