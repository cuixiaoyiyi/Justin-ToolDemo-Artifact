package cn.ac.ios.justin.symexec;

import java.util.List;

/**
 * The receivers a method can be called on, as concrete objects whose fields can be looked at.
 *
 * <p>Symbolic execution treats a field of the receiver as an unknown. A path condition such as
 * {@code this.size > 0} is then satisfiable whatever the receiver of the test really holds, and the test does
 * not follow the path. With the candidates at hand the executor asks, for a completed path, which of them the
 * path is feasible for: the fields the path read take the candidate's values, the solver finds the arguments.
 *
 * <p>Implemented by the engine, which builds the candidates in its sandbox. Reading is all that happens to
 * them; the tests that come out call public methods only.
 */
public interface ReceiverStates {

    /** Number of candidate receivers; candidate 0 is the one used when nothing else is known. */
    int candidates();

    /**
     * Objects other than the receiver whose state a path can depend on: {@code arg:i}, the i-th argument of the
     * method, and {@code ctor:i}, the i-th argument of the constructor the receiver is built with. The receiver
     * itself is root "".
     */
    default int candidates(String root) { return root.isEmpty() ? candidates() : 0; }

    /** As {@link #read(int, List)}, for candidate k of the given root; the path starts below the root object. */
    default Object read(String root, int k, List<String> path) { return root.isEmpty() ? read(k, path) : UNKNOWN; }

    /**
     * The value at the end of a chain of fields, starting at candidate k as it is after its construction.
     *
     * @param k    the candidate; ignored (and possibly -1) when the chain starts at a static field
     * @param path field names, each as {@code declaringClass#name}; empty for the candidate itself (a {@link Ref}
     *             with its class); a first segment {@code static:declaringClass#name}
     *             starts at a static field, whose value is reported only when the field is final
     * @return a boxed primitive, a String, an array of primitives or strings, {@code null} for a null reference,
     *         a {@link Ref} for any other object, {@link #UNKNOWN} when the value cannot be read, or
     *         {@link #BROKEN} when a link before the last one is null (the path would have thrown)
     */
    Object read(int k, List<String> path);

    /** A non-null object that is not a string or an array the executor models: only its class is reported. */
    record Ref(String className) {}

    /** A collection read through its public interface: size and, for strings and boxed primitives, the elements in iteration order. */
    record Elements(String className, int size, List<Object> elements) {}

    Object UNKNOWN = new Object() { @Override public String toString() { return "UNKNOWN"; } };
    Object BROKEN = new Object() { @Override public String toString() { return "BROKEN"; } };
}
