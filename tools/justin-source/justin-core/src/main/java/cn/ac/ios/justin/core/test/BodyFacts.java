package cn.ac.ios.justin.core.test;

import java.util.List;
import java.util.Set;

/**
 * What the analysis layer knows about a method body. Produced by justin-analysis (Jimple), consumed by
 * strategies, the combiner and the oracle without a dependency on SootUp.
 * @param explicitThrows      binary names of exception types the body creates and throws itself
 * @param preconditionChecks  the body calls requireNonNull / Validate / checkArgument style helpers
 * @param available           false when no body could be analyzed (native, missing, SootUp failure)
 * @param intConstants        int constants the body compares or computes with (seed values)
 * @param relations           parameter relations found in the body (array/index, ranges, keys...)
 * @param neverReturnsNull    every return statement returns something provably non-null
 * @param pure                no field or array writes, no calls outside a small immutable whitelist
 */
public record BodyFacts(Set<String> explicitThrows, boolean preconditionChecks, boolean available,
                        Set<Integer> intConstants, Set<Long> longConstants, Set<Double> doubleConstants, Set<String> stringConstants,
                        List<Relation> relations, boolean neverReturnsNull, boolean pure) {

    public static final BodyFacts NONE = new BodyFacts(Set.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), List.of(), false, false);

    public BodyFacts {
        explicitThrows = explicitThrows == null ? Set.of() : Set.copyOf(explicitThrows);
        intConstants = intConstants == null ? Set.of() : Set.copyOf(intConstants);
        longConstants = longConstants == null ? Set.of() : Set.copyOf(longConstants);
        doubleConstants = doubleConstants == null ? Set.of() : Set.copyOf(doubleConstants);
        stringConstants = stringConstants == null ? Set.of() : Set.copyOf(stringConstants);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /** Backwards-compatible constructor without analysis extras. */
    public BodyFacts(Set<String> explicitThrows, boolean preconditionChecks, boolean available) {
        this(explicitThrows, preconditionChecks, available, Set.of(), Set.of(), Set.of(), Set.of(), List.of(), false, false);
    }

    public BodyFacts withRelations(List<Relation> rs) {
        return new BodyFacts(explicitThrows, preconditionChecks, available, intConstants, longConstants, doubleConstants, stringConstants, rs, neverReturnsNull, pure);
    }
}
