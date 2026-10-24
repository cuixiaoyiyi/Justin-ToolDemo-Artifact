package cn.ac.ios.justin.api;

import cn.ac.ios.justin.runner.CoverageAnalyzer;
import cn.ac.ios.justin.runner.JUnitXmlParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Structured outcome of one run. Serialized to reports/report.json (and rendered to report.md) by the engine. */
public record GenerationResult(
        String project,
        String scope,
        long seed,
        String runDir,
        List<String> strategiesEnabled,
        Map<String, Integer> testsByStrategy,
        NwayCoverage nway,
        ProbeCoverage probes,
        VerdictSummary verdicts,
        LlmSummary llm,
        SymbolicSummary symbolic,
        List<ClassResult> classes,
        Map<String, Integer> rejectedTuples,
        CompileSummary compile,
        ExecutionSummary execution,
        CoverageAnalyzer.Report coverage,
        Timings timings,
        List<String> warnings) {

    /** Strength-n interaction coverage achieved by the emitted tuples, summed over all methods. */
    public record NwayCoverage(int strength, long totalInteractions, long coveredInteractions) {
        public double ratio() { return totalInteractions == 0 ? 1.0 : (double) coveredInteractions / totalInteractions; }
    }

    /** Coverage measured in the generation sandbox (JaCoCo probes), before compile and forked execution. */
    public record ProbeCoverage(long totalProbes, long coveredProbes, Map<String, StrategyStats> byStrategy) {
        public double ratio() { return totalProbes == 0 ? 0 : (double) coveredProbes / totalProbes; }
    }

    /** exclusiveProbes: probes no test without this strategy reaches. */
    public record StrategyStats(int tests, long probes, long exclusiveProbes) {}

    /**
     * Cost and yield of model-inferred annotations. Null when the LLM was not enabled. endpoint names the
     * gateway and model, never the key; wallMillis is time spent inside model calls (calls run in parallel,
     * so it can exceed the generate phase's elapsed time).
     */
    public record LlmSummary(String endpoint, long calls, long cacheHits, long failures, Map<String, Integer> failureReasons,
                             long methodsAugmented, long wallMillis, long averageMillis, long maxMillis, long inputTokens, long outputTokens) {}

    /**
     * Symbolic execution counters. mode: "cascade" (after the other strategies, on their uncovered lines)
     * or "direct" (every method). targetsReached/targetsTotal: uncovered lines the
     * cheaper strategies left and how many the symbolic tests then reached; unmodeledCalls: callees
     * that produced fresh symbols (candidates for summaries).
     */
    /**
     * What the solver was asked during symbolic execution. callsWithBridge: calls whose path condition carried a
     * conversion between int and string lengths or indices; assertionsLifted: assertions restated over the integers
     * before they went to the solver (0 with --no-bridge-lift); bridgeLift: whether that rewrite was on.
     */
    public record SolverSummary(int calls, long millis, int timeouts, int callsWithBridge, int assertionsLifted, int variablesAsIntegers, boolean bridgeLift) {}

    /**
     * How completed paths that read fields were matched with concrete state. paths: paths that read fields of the
     * receiver; fitted: a candidate receiver makes the path feasible, and the test uses that receiver; unfitted:
     * feasible only for field values no candidate has (the test runs on the default receiver and may miss the
     * path); withStatics: paths solved with the values of the static final fields they read; unmetFields: the
     * fields of the unfitted paths, with the number of paths.
     */
    public record ReceiverSummary(int methods, int candidates, int paths, int fitted, int unfitted, int withStatics, Map<String, Integer> unmetFields,
                                  int sequences, int sequenceTuples) {}

    public record SymbolicSummary(String mode, int methods, int pathsExplored, int pathsSatisfiable, int pathsUnsat, int solverTimeouts, int loopBoundHits,
                                  int stepBoundHits, int pathBoundHits, int timeBudgetHits, int tuples, int testsKept, int targetsReached, int targetsTotal,
                                  long millis, Map<String, Integer> unmodeledCalls, int explosions, int inlinedCalls, int unfinished,
                                  Map<String, Integer> targetsByCause, Map<String, Integer> reachedByCause, RegexSummary regex, SolverSummary solver, ReceiverSummary receivers) {
        /** targetsByCause / reachedByCause: targets grouped by what their branch condition depends on (string, collection, object-state, other-call, modeled, unknown). */
        public Map<String, Integer> targetsByCauseOrEmpty() { return targetsByCause == null ? Map.of() : targetsByCause; }
        public Map<String, Integer> reachedByCauseOrEmpty() { return reachedByCause == null ? Map.of() : reachedByCause; }
    }

    /**
     * The regex channel of stage C (regex-guided generation for string parameters, run before the solver):
     * methods it applied to, string paths sliced, tuples produced (path tuples and virtual-pattern
     * combinations), tests kept, target lines it reached, and its time.
     */
    public record RegexSummary(int methods, int stringPaths, int tuples, int patternTuples, int testsKept, int targetsReached, long millis) {}

    /**
     * Symbolic execution of one method (plan 7.3 report): budget allocated over the rounds and time used,
     * satisfiable paths, tuples and kept tests, target lines reached, same-class calls inlined, why it
     * stopped ("time", "paths", "no-gain", "solver-timeouts", or null when the work list ran empty).
     */
    public record SymMethod(String method, long allocatedMs, long usedMs, int pathsSatisfiable, int tuples, int testsKept, int targets, int targetsReached,
                            int inlinedCalls, String stopReason, boolean finished, int loopBoundHits, int solverTimeouts) {}

    /** How the exception tests were classified; suspects are listed with their evidence. */
    public record VerdictSummary(int expected, int suspect, int unknown, List<Suspect> suspects) {}

    public record Suspect(String testClass, String test, String targetMethod, String exception, String evidence, String strategy, String source) {}

    /**
     * One generated test. outcomeKind is what the sandbox observed ("returned", "threw:NPE");
     * verdict/evidence are set for exception tests; executionStatus/failure are filled after the forked run.
     */
    public record TestInfo(String name, String targetMethod, String strategy, String source, int assertions, String outcomeKind,
                           String verdict, String evidence, String executionStatus, Failure failure) {
        public TestInfo withExecution(String status, Failure f) {
            return new TestInfo(name, targetMethod, strategy, source, assertions, outcomeKind, verdict, evidence, status, f);
        }
    }

    public record Failure(String exceptionType, String message, String stackTrace) {}

    /** Per target method: how many tests, how they were classified, how they ran. */
    public record MethodSummary(String method, int tests, int returnTests, int exceptionTests, int expected, int suspect, int unknown,
                                long passed, long failed) {}

    /** A line the kept tests leave (partly) uncovered, as measured in the generation sandbox. */
    public record Uncovered(String method, int line, int missedBranches, int totalBranches, boolean lineUncovered) {}

    /**
     * uncovered: sandbox-measured targets left for directed strategies; sandboxBranches* are the class
     * totals behind them; symbolic: per-method symbolic execution accounting (empty when it did not run).
     */
    public record ClassResult(String binaryName, String testClass, Path sourceFile, int methodsConsidered, int methodsTested,
                              List<TestInfo> tests, List<MethodSummary> methods,
                              int sandboxBranchesCovered, int sandboxBranchesTotal, List<Uncovered> uncovered, List<SymMethod> symbolic) {
        public ClassResult(String binaryName, String testClass, Path sourceFile, int methodsConsidered, int methodsTested,
                           List<TestInfo> tests, List<MethodSummary> methods) {
            this(binaryName, testClass, sourceFile, methodsConsidered, methodsTested, tests, methods, 0, 0, List.of(), List.of());
        }
        public ClassResult(String binaryName, String testClass, Path sourceFile, int methodsConsidered, int methodsTested,
                           List<TestInfo> tests, List<MethodSummary> methods,
                           int sandboxBranchesCovered, int sandboxBranchesTotal, List<Uncovered> uncovered) {
            this(binaryName, testClass, sourceFile, methodsConsidered, methodsTested, tests, methods, sandboxBranchesCovered, sandboxBranchesTotal, uncovered, List.of());
        }
        public List<SymMethod> symbolicOrEmpty() { return symbolic == null ? List.of() : symbolic; }
        public ClassResult withTests(List<TestInfo> t, List<MethodSummary> m) {
            return new ClassResult(binaryName, testClass, sourceFile, methodsConsidered, methodsTested, t, m, sandboxBranchesCovered, sandboxBranchesTotal, uncovered, symbolic);
        }
        public ClassResult withUncovered(int covered, int total, List<Uncovered> u) {
            return new ClassResult(binaryName, testClass, sourceFile, methodsConsidered, methodsTested, tests, methods, covered, total, u, symbolic);
        }
        public ClassResult withSymbolic(List<SymMethod> s) {
            return new ClassResult(binaryName, testClass, sourceFile, methodsConsidered, methodsTested, tests, methods, sandboxBranchesCovered, sandboxBranchesTotal, uncovered, s);
        }
    }

    /** prunedTests: "TestClass#method: javac message" for every test removed to make its file compile. */
    public record CompileSummary(int filesCompiled, int filesFailed, Map<String, List<String>> failures, List<String> prunedTests) {}

    /**
     * failures: tests that failed in the full run and still fail alone. orderDependent: tests that failed
     * in the full run but pass in isolation; they were removed from the emitted sources.
     */
    public record ExecutionSummary(int total, long passed, long failed, long errors, long skipped, boolean timedOut,
                                   List<JUnitXmlParser.TestResult> failures, List<String> orderDependent) {
        public ExecutionSummary(int total, long passed, long failed, long errors, long skipped, boolean timedOut, List<JUnitXmlParser.TestResult> failures) {
            this(total, passed, failed, errors, skipped, timedOut, failures, List.of());
        }
    }

    /** llmMillis: wall time inside model calls (summed over parallel calls), already part of generateMillis's span. */
    public record Timings(long scanMillis, long generateMillis, long emitMillis, long compileMillis, long runMillis, long coverageMillis, long totalMillis, long llmMillis) {
        public Timings(long scanMillis, long generateMillis, long emitMillis, long compileMillis, long runMillis, long coverageMillis, long totalMillis) {
            this(scanMillis, generateMillis, emitMillis, compileMillis, runMillis, coverageMillis, totalMillis, 0);
        }
    }

    public int totalTests() { return classes.stream().mapToInt(c -> c.tests().size()).sum(); }
    public int methodsConsidered() { return classes.stream().mapToInt(ClassResult::methodsConsidered).sum(); }
    public int methodsTested() { return classes.stream().mapToInt(ClassResult::methodsTested).sum(); }
}
