package cn.ac.ios.justin.core.config;

import java.util.List;

/** Tunables that apply to a whole generation run. Strategy-specific settings live in their own records. */
public record GenerationConfig(
        long seed,
        List<String> strategies,
        int tuplesPerMethod,
        int maxDepth,
        boolean includePrivate,
        boolean includeProtected,
        long invocationTimeoutMillis,
        long classTimeBudgetMillis,
        int javaVersion,
        boolean blockIo,
        int strength,
        int maxDomain,
        boolean minimize) {

    public static final long DEFAULT_SEED = 42L;

    public static GenerationConfig defaults() {
        return new GenerationConfig(DEFAULT_SEED, List.of("random"), 20, 3, false, true, 2000, 60_000, 17, true, 1, 12, true);
    }

    /** Backwards-compatible constructor without the combination settings (strength 2, domain cap 12). */
    public GenerationConfig(long seed, List<String> strategies, int tuplesPerMethod, int maxDepth, boolean includePrivate, boolean includeProtected,
                            long invocationTimeoutMillis, long classTimeBudgetMillis, int javaVersion, boolean blockIo) {
        this(seed, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, 1, 12, true);
    }

    /** n-way strength for the combiner (1 = each-choice, 2 = pairwise, ...) and the cap on candidates per slot it considers. */
    public GenerationConfig withStrength(int n, int domainCap) {
        return new GenerationConfig(seed, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, n, domainCap, minimize);
    }

    public GenerationConfig withSeed(long s) {
        return new GenerationConfig(s, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, strength, maxDomain, minimize);
    }
    public GenerationConfig withStrategies(List<String> s) {
        return new GenerationConfig(seed, s, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, strength, maxDomain, minimize);
    }
    public GenerationConfig withTuplesPerMethod(int n) {
        return new GenerationConfig(seed, strategies, n, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, strength, maxDomain, minimize);
    }
    public GenerationConfig withIncludePrivate(boolean b) {
        return new GenerationConfig(seed, strategies, tuplesPerMethod, maxDepth, b, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, strength, maxDomain, minimize);
    }
    public GenerationConfig withJavaVersion(int v) {
        return new GenerationConfig(seed, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, v, blockIo, strength, maxDomain, minimize);
    }
    /** Drop tests that add no probe coverage beyond earlier tests of the same class. */
    public GenerationConfig withMinimize(boolean m) {
        return new GenerationConfig(seed, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, blockIo, strength, maxDomain, m);
    }
    public GenerationConfig withBlockIo(boolean b) {
        return new GenerationConfig(seed, strategies, tuplesPerMethod, maxDepth, includePrivate, includeProtected, invocationTimeoutMillis, classTimeBudgetMillis, javaVersion, b, strength, maxDomain, minimize);
    }
}
