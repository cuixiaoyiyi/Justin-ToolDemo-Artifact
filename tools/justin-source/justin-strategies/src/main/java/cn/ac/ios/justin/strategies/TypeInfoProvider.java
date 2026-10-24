package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.ClassTarget;

import java.util.List;
import java.util.Optional;

/**
 * What a strategy may ask about types beyond the target itself. Backed by the scan index (and, for
 * JDK and dependency classes, by reflection) in justin-api; strategies never load classes themselves.
 */
public interface TypeInfoProvider {
    Optional<ClassTarget> lookup(String binaryName);

    /** Enum constant names, or empty if the type is not an enum or unknown. */
    List<String> enumConstants(String binaryName);

    /** Concrete, instantiable subtypes known for an abstract class or interface (project classes first). */
    List<String> knownImplementations(String binaryName);

    static TypeInfoProvider empty() {
        return new TypeInfoProvider() {
            @Override public Optional<ClassTarget> lookup(String n) { return Optional.empty(); }
            @Override public List<String> enumConstants(String n) { return List.of(); }
            @Override public List<String> knownImplementations(String n) { return List.of(); }
        };
    }
}
