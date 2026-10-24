package cn.ac.ios.justin.api;

/** Progress callbacks for front-ends. All methods have no-op defaults; implementations must be cheap. */
public interface GenerationListener {
    default void phase(String name) {}
    default void classStarted(String binaryName, int index, int total) {}
    default void classFinished(String binaryName, int testsGenerated) {}
    default void methodFinished(String methodId, int tuples, int tests) {}
    default void warning(String message) {}
    default boolean isCancelled() { return false; }

    GenerationListener NONE = new GenerationListener() {};
}
