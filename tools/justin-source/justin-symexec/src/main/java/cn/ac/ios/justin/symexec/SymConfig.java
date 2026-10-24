package cn.ac.ios.justin.symexec;

/**
 * Bounds and budgets for symbolic execution (plan sections 7.2 and 7.3). Every dimension is capped
 * so exploration always terminates; hits are counted and reported.
 *
 * <p>Budgets form a hierarchy: a class gets {@code classTimeBudgetMs}; inside it every method first
 * gets a start budget ({@code methodStartBudgetMs}, weighted by branch count) in round one, then the
 * remaining class budget is redistributed in round two to the methods that still have targets, never
 * exceeding {@code methodTimeBudgetMs} per method.
 *
 * @param mode                  cascade (after the other strategies, on their uncovered lines) or direct (every method)
 * @param maxPathsPerMethod     satisfiable paths after which a method stops
 * @param maxStepsPerPath       statements per path
 * @param maxLoopIterations     visits of a branching statement per path (loop unrolling)
 * @param maxConstraintsPerPath path condition length
 * @param maxArrayLength        symbolic array length bound
 * @param maxStringLength       symbolic string length bound
 * @param solverTimeoutMs       per solver call
 * @param methodTimeBudgetMs    per-method maximum over both rounds
 * @param methodStartBudgetMs   per-method budget in round one
 * @param classTimeBudgetMs     per-class maximum over all its methods
 * @param explosionNoGainPaths  consecutive satisfiable paths reaching no new target line before a method is cut (0 = off)
 * @param inlineDepth           depth of same-class call inlining (0 = calls return fresh symbols)
 * @param regexChannel          regex-guided generation for string parameters runs before the solver on the same targets
 * @param objects               how objects are modelled (see ObjectModel)
 * @param bridgeLift            conversions between bit-vectors and integers are taken off the string terms before a
 *                              path condition goes to the solver (see BridgeLift)
 */
public record SymConfig(
        Mode mode,
        int maxPathsPerMethod,
        int maxStepsPerPath,
        int maxLoopIterations,
        int maxConstraintsPerPath,
        int maxArrayLength,
        int maxStringLength,
        int solverTimeoutMs,
        long methodTimeBudgetMs,
        long methodStartBudgetMs,
        long classTimeBudgetMs,
        int explosionNoGainPaths,
        int inlineDepth,
        boolean regexChannel,
        boolean bridgeLift,
        ObjectModel objects) {

    /**
     * How objects are modelled.
     *
     * @param receiverCandidates receivers a completed path is fitted to: their fields, read in the sandbox, take
     *                           the place of the symbols the path read (0 = fields stay free symbols)
     * @param sequenceLength     longest sequence of calls of public methods that builds a receiver for the fields
     *                           no candidate provided (0 = no sequences)
     * @param collections        summaries for the collections of java.util
     * @param crossClassInline   calls of methods of other project classes are inlined like same-class calls
     * @param types              instanceof, casts and virtual calls follow the class of the object
     */
    public record ObjectModel(int receiverCandidates, int sequenceLength, boolean collections, boolean crossClassInline, boolean types) {
        public static ObjectModel defaults() { return new ObjectModel(8, 3, true, true, true); }
        public static ObjectModel off() { return new ObjectModel(0, 0, false, false, false); }

        /** From a list of names: receiver, sequence, collections, inline, types; all and none. */
        public static ObjectModel parse(String list) {
            ObjectModel all = new ObjectModel(8, 3, true, true, true);
            if (list == null || list.isBlank() || list.equalsIgnoreCase("default")) return defaults();
            if (list.equalsIgnoreCase("all")) return all;
            if (list.equalsIgnoreCase("none")) return off();
            int receivers = 0, sequence = 0;
            boolean collections = false, inline = false, types = false;
            for (String name : list.split(",")) {
                switch (name.trim().toLowerCase()) {
                    case "receiver" -> receivers = all.receiverCandidates();
                    case "sequence" -> { receivers = all.receiverCandidates(); sequence = all.sequenceLength(); }
                    case "collections" -> collections = true;
                    case "inline" -> inline = true;
                    case "types" -> types = true;
                    default -> throw new IllegalArgumentException("unknown object model '" + name.trim() + "' (receiver, sequence, collections, inline, types, all, none)");
                }
            }
            return new ObjectModel(receivers, sequence, collections, inline, types);
        }
    }

    /**
     * How symbolic execution is coupled to the other strategies. CASCADE: runs after them, only on the
     * lines they left uncovered (stage C of the cascade). DIRECT: runs on every method as a strategy in
     * its own right, exploring all paths within the bounds, whether or not other strategies are enabled.
     */
    public enum Mode { CASCADE, DIRECT }

    public static SymConfig defaults() {
        return new SymConfig(Mode.CASCADE, 100, 5000, 3, 300, 8, 32, 2000, 30_000, 1000, 60_000, 50, 1, true, true, ObjectModel.defaults());
    }

    public SymConfig withMode(Mode m) {
        return new SymConfig(m, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withMethodTimeBudgetMs(long ms) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, ms, Math.min(methodStartBudgetMs, ms), classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withMethodStartBudgetMs(long ms) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, ms, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withClassTimeBudgetMs(long ms) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, ms, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withMaxPathsPerMethod(int n) {
        return new SymConfig(mode, n, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withExplosionNoGainPaths(int n) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, n, inlineDepth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withInlineDepth(int depth) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, depth, regexChannel, bridgeLift, objects);
    }

    public SymConfig withRegexChannel(boolean on) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, on, bridgeLift, objects);
    }

    public SymConfig withBridgeLift(boolean on) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, on, objects);
    }

    public SymConfig withObjects(ObjectModel o) {
        return new SymConfig(mode, maxPathsPerMethod, maxStepsPerPath, maxLoopIterations, maxConstraintsPerPath, maxArrayLength, maxStringLength,
                solverTimeoutMs, methodTimeBudgetMs, methodStartBudgetMs, classTimeBudgetMs, explosionNoGainPaths, inlineDepth, regexChannel, bridgeLift, o);
    }

    public boolean direct() { return mode == Mode.DIRECT; }
}
