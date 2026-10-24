package cn.ac.ios.justin.api;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.FieldInfo;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.strategies.TypeInfoProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** TypeInfoProvider over the scanned target classes, with subtype and enum indexes. */
public final class ScanIndex implements TypeInfoProvider {
    private static final int ACC_ENUM = 0x4000;

    private final Map<String, ClassTarget> byName = new HashMap<>();
    private final Map<String, List<String>> subtypes = new HashMap<>();

    public ScanIndex(List<ClassTarget> classes) {
        for (ClassTarget c : classes) byName.put(c.binaryName(), c);
        // every concrete public class is an implementation of all its ancestors, not only of the types its own
        // declaration names: HashBag implements Bag through AbstractMapBag, and Bag must find it
        for (ClassTarget c : classes) {
            if (c.isInterface() || c.isAbstract() || c.isAnonymous() || !c.isPublic()) continue;
            java.util.Set<String> ancestors = new java.util.LinkedHashSet<>();
            java.util.ArrayDeque<ClassTarget> work = new java.util.ArrayDeque<>();
            work.add(c);
            while (!work.isEmpty()) {
                ClassTarget cur = work.poll();
                List<TypeRef> supers = new ArrayList<>(cur.interfaces());
                if (cur.superClass() != null) supers.add(cur.superClass());
                for (TypeRef t : supers) {
                    String name = t.erasureName();
                    if (name.equals("java.lang.Object") || !ancestors.add(name)) continue;
                    ClassTarget up = byName.get(name);
                    if (up != null) work.add(up);
                }
            }
            for (String a : ancestors) subtypes.computeIfAbsent(a, k -> new ArrayList<>()).add(c.binaryName());
        }
        for (List<String> l : subtypes.values()) java.util.Collections.sort(l);
    }

    public List<ClassTarget> all() { return new ArrayList<>(byName.values()); }

    @Override public Optional<ClassTarget> lookup(String binaryName) { return Optional.ofNullable(byName.get(binaryName)); }

    @Override public List<String> enumConstants(String binaryName) {
        ClassTarget c = byName.get(binaryName);
        if (c == null || !c.isEnum()) return List.of();
        List<String> out = new ArrayList<>();
        for (FieldInfo f : c.fields()) if ((f.access() & ACC_ENUM) != 0) out.add(f.name());
        return out;
    }

    @Override public List<String> knownImplementations(String binaryName) {
        return subtypes.getOrDefault(binaryName, List.of());
    }
}
