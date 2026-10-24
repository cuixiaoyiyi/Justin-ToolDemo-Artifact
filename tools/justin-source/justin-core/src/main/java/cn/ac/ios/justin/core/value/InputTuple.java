package cn.ac.ios.justin.core.value;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A complete set of inputs for one invocation of the target: receiver (null for static methods and
 * constructors), one value per parameter, and the bindings chosen for method-level type variables.
 * @param source     how the tuple was assembled (each-choice, random-fill, symbolic...)
 * @param strategies names of the strategies that contributed at least one value
 */
public record InputTuple(MethodTarget target, Value receiver, List<Value> args, Map<String, TypeRef> typeBindings, String source, Set<String> strategies) {
    public InputTuple {
        args = List.copyOf(args);
        typeBindings = typeBindings == null ? Map.of() : Map.copyOf(typeBindings);
        strategies = strategies == null ? Set.of() : Set.copyOf(new TreeSet<>(strategies));
        if (args.size() != target.arity()) {
            throw new IllegalArgumentException("arity mismatch for " + target.id() + ": " + args.size());
        }
    }

    public InputTuple(MethodTarget target, Value receiver, List<Value> args, Map<String, TypeRef> typeBindings, String source) {
        this(target, receiver, args, typeBindings, source, Set.of());
    }

    /** Sorted, '+'-joined strategy names, e.g. "random+special". */
    public String strategyLabel() { return String.join("+", new TreeSet<>(strategies)); }
}
