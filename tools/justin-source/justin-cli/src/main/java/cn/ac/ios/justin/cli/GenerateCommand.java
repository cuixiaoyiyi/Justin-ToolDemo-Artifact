package cn.ac.ios.justin.cli;

import cn.ac.ios.justin.api.Engine;
import cn.ac.ios.justin.api.GenerationListener;
import cn.ac.ios.justin.api.GenerationRequest;
import cn.ac.ios.justin.api.GenerationResult;
import cn.ac.ios.justin.api.ProjectModel;
import cn.ac.ios.justin.core.config.GenerationConfig;
import cn.ac.ios.justin.runner.CoverageAnalyzer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "generate", mixinStandardHelpOptions = true, description = "Generate JUnit 5 tests and optionally compile, run and measure coverage.")
public final class GenerateCommand implements Callable<Integer> {

    @Mixin CommonOptions common;

    @Option(names = "--out", required = true, description = "Base output directory; each run gets its own subdirectory")
    Path out;

    @Option(names = "--label", description = "Name of the run subdirectory (default: <timestamp>_<strategies>_seed<seed>)")
    String label;

    @Option(names = "--strategies", split = ",", defaultValue = "random,special,annotation,static,sequence", description = "Strategies to use: random, special, annotation, static, sequence (container and array fill levels), symbolic (default: the first five, with symbolic execution in cascade mode on top); listing symbolic switches it to direct mode; llm values appear when --llm-config is given")
    List<String> strategies;

    @Option(names = "--seed", defaultValue = "42", description = "Random seed (default 42)")
    long seed;

    @Option(names = "--tuples", defaultValue = "20", description = "Max legal input tuples per method (illegal ones get the same budget)")
    int tuples;

    @Option(names = "--strength", defaultValue = "1", description = "n-way combination strength: 1 each-choice, 2 pairwise, ... (default 1; raise --tuples with it)")
    int strength;

    @Option(names = "--domain-cap", defaultValue = "12", description = "Max candidates per parameter the combiner considers")
    int domainCap;

    @Option(names = "--timeout", defaultValue = "2000", description = "Per-invocation timeout in ms during generation")
    long timeout;

    @Option(names = "--include-private", description = "Also generate for private methods")
    boolean includePrivate;

    @Option(names = "--threads", defaultValue = "1", description = "Classes generated in parallel (default 1; results per class are seeded, so the outcome does not depend on the thread count)")
    int threads;

    @Option(names = "--run", description = "Compile, execute in a forked JVM and compute coverage")
    boolean run;

    @Option(names = "--no-minimize", negatable = false, description = "Keep tests that add no probe coverage over earlier tests of the same class")
    boolean noMinimize;

    @Option(names = "--allow-io", negatable = false, description = "Do not block file writes, sockets, processes, System.exit and thread starts during generation")
    boolean allowIo;

    @Option(names = "--llm-config", description = "Properties file (baseUrl, model, apiKey, ...) enabling model-inferred annotations; keep it out of version control")
    Path llmConfig;

    @Option(names = "--no-symbolic", negatable = false, description = "Skip symbolic execution altogether (same as --symbolic off)")
    boolean noSymbolic;

    @Option(names = "--symbolic", description = "How symbolic execution is coupled to the other strategies: cascade (after them, only on the lines they leave uncovered), direct (every method, as a strategy in its own right; the default when --strategies lists symbolic), off")
    String symbolicMode;

    @Option(names = "--sym-budget", defaultValue = "5000", description = "Symbolic execution: maximum time per method in ms over both rounds (default 5000)")
    long symBudget;

    @Option(names = "--sym-start", defaultValue = "1000", description = "Symbolic execution: round-one budget per method in ms; the rest of the class budget goes to methods that still make progress (default 1000)")
    long symStart;

    @Option(names = "--sym-class-budget", defaultValue = "60000", description = "Symbolic execution: maximum time per class in ms (default 60000)")
    long symClassBudget;

    @Option(names = "--sym-inline", defaultValue = "1", description = "Symbolic execution: depth of same-class call inlining, 0 to treat every call as unknown (default 1)")
    int symInline;

    @Option(names = "--sym-no-gain", defaultValue = "50", description = "Symbolic execution: consecutive paths without a new target line after which a method is cut, 0 to disable (default 50)")
    int symNoGain;

    @Option(names = "--sym-objects", defaultValue = "default", description = "Symbolic execution: how objects are modelled, a list of receiver (paths are fitted to the fields of concrete receivers), sequence (receivers built by calls of public methods), collections (summaries for java.util collections), inline (calls into other project classes are followed), types (instanceof and virtual calls); or all, none, default")
    String symObjects;

    private cn.ac.ios.justin.symexec.SymConfig.ObjectModel objectModel() {
        try {
            return cn.ac.ios.justin.symexec.SymConfig.ObjectModel.parse(symObjects);
        } catch (IllegalArgumentException e) {
            throw new picocli.CommandLine.ParameterException(new picocli.CommandLine(this), "--sym-objects: " + e.getMessage());
        }
    }

    @Option(names = "--no-bridge-lift", negatable = false, description = "Symbolic stage: send path conditions to the solver as they are, with the conversions between int and string lengths or indices left on the string terms (slower; for comparison)")
    boolean noBridgeLift;

    @Option(names = "--no-regex", negatable = false, description = "Symbolic stage: skip the regex channel (regex-guided inputs for string parameters, which by default runs before the solver)")
    boolean noRegex;

    @Option(names = "--sym-paths", defaultValue = "50", description = "Max satisfiable paths per method for symbolic execution (default 50)")
    int symPaths;

    /** The request these options describe; shared by the CLI and the daemon (which receives the same option strings). */
    public GenerationRequest buildRequest() throws Exception {
        ProjectModel project = common.projectModel();
        GenerationConfig config = GenerationConfig.defaults()
                .withSeed(seed).withStrategies(strategies).withTuplesPerMethod(tuples).withIncludePrivate(includePrivate)
                .withJavaVersion(project.javaVersion());
        config = new GenerationConfig(config.seed(), config.strategies(), config.tuplesPerMethod(), config.maxDepth(),
                config.includePrivate(), config.includeProtected(), timeout, config.classTimeBudgetMillis(), config.javaVersion(), config.blockIo())
                .withStrength(strength, domainCap).withMinimize(!noMinimize).withBlockIo(!allowIo);
        String mode = symbolicMode == null ? (strategies.contains("symbolic") ? "direct" : "cascade") : symbolicMode.toLowerCase();
        if (noSymbolic) mode = "off";
        cn.ac.ios.justin.symexec.SymConfig sym = switch (mode) {
            case "off" -> null;
            case "cascade", "direct" -> cn.ac.ios.justin.symexec.SymConfig.defaults()
                    .withMode(cn.ac.ios.justin.symexec.SymConfig.Mode.valueOf(mode.toUpperCase()))
                    .withMethodTimeBudgetMs(symBudget).withMaxPathsPerMethod(symPaths)
                    .withMethodStartBudgetMs(Math.min(symStart, symBudget)).withClassTimeBudgetMs(symClassBudget)
                    .withInlineDepth(symInline).withExplosionNoGainPaths(symNoGain).withRegexChannel(!noRegex).withBridgeLift(!noBridgeLift)
                    .withObjects(objectModel());
            default -> throw new picocli.CommandLine.ParameterException(new picocli.CommandLine(this), "--symbolic expects cascade, direct or off, got: " + symbolicMode);
        };
        return new GenerationRequest(project, common.scope(), config, out, label, run, llmConfig, sym, Math.max(1, threads));
    }

    @Override public Integer call() throws Exception {
        if (run) {
            // --run compiles the tests: say so before minutes of generation, not after
            java.nio.file.Path javac = java.nio.file.Path.of(System.getProperty("java.home"), "bin", java.io.File.separatorChar != '/' ? "javac.exe" : "javac");
            if (!java.nio.file.Files.exists(javac)) {
                System.err.println("[justin] --run needs a JDK, but " + System.getProperty("java.home") + " has no javac (a JRE?). Set JAVA_HOME to a JDK 17 or newer, or drop --run to only generate.");
                return 2;
            }
        }
        GenerationRequest req = buildRequest();
        GenerationListener listener = new GenerationListener() {
            @Override public void phase(String name) { System.out.println("[justin] phase: " + name); }
            @Override public void classStarted(String n, int i, int total) { System.out.println("[justin] (" + i + "/" + total + ") " + n); }
            @Override public void classFinished(String n, int tests) { System.out.println("[justin]   -> " + tests + " tests"); }
        };
        GenerationResult r = new Engine().run(req, listener);
        print(r);
        return 0;
    }

    private static void print(GenerationResult r) {
        System.out.println();
        System.out.println("Project:   " + r.project() + "   scope: " + r.scope() + "   seed: " + r.seed());
        System.out.println("Run dir:   " + r.runDir());
        System.out.println("Classes:   " + r.classes().size() + "   tests generated: " + r.totalTests() + "   by strategy: " + r.testsByStrategy());
        if (!r.rejectedTuples().isEmpty()) System.out.println("Rejected:  " + r.rejectedTuples());
        if (r.probes() != null) {
            System.out.printf("Probes:    %d/%d covered in the sandbox (%.1f%%)%n", r.probes().coveredProbes(), r.probes().totalProbes(), 100 * r.probes().ratio());
            r.probes().byStrategy().forEach((s, st) -> System.out.printf("           %-12s tests %5d  probes %6d  exclusive %5d%n", s, st.tests(), st.probes(), st.exclusiveProbes()));
        }
        if (r.nway() != null) System.out.printf("%d-way:     %d/%d interactions covered (%.1f%%)%n", r.nway().strength(), r.nway().coveredInteractions(), r.nway().totalInteractions(), 100 * r.nway().ratio());
        if (r.verdicts() != null) System.out.println("Verdicts:  exception tests expected " + r.verdicts().expected() + ", suspect " + r.verdicts().suspect() + ", unknown " + r.verdicts().unknown());
        if (r.compile() != null) System.out.println("Compiled:  " + r.compile().filesCompiled() + " files, failed " + r.compile().filesFailed());
        if (r.execution() != null) {
            var e = r.execution();
            System.out.println("Executed:  " + e.total() + " tests, passed " + e.passed() + ", failed " + e.failed() + ", errors " + e.errors()
                    + (e.orderDependent().isEmpty() ? "" : ", order-dependent removed " + e.orderDependent().size()) + (e.timedOut() ? " (TIMED OUT)" : ""));
        }
        if (r.coverage() != null) {
            CoverageAnalyzer.Report c = r.coverage();
            System.out.printf("Coverage:  lines %d/%d (%.1f%%)  branches %d/%d (%.1f%%)  methods %d/%d (%.1f%%)%n",
                    c.lines().covered(), c.lines().total(), 100 * c.lines().ratio(),
                    c.branches().covered(), c.branches().total(), 100 * c.branches().ratio(),
                    c.methods().covered(), c.methods().total(), 100 * c.methods().ratio());
        }
        var t = r.timings();
        System.out.printf("Time:      scan %dms, generate %dms, emit %dms, compile %dms, run %dms, coverage %dms, total %dms%n",
                t.scanMillis(), t.generateMillis(), t.emitMillis(), t.compileMillis(), t.runMillis(), t.coverageMillis(), t.totalMillis());
        if (r.symbolic() != null) {
            var y = r.symbolic();
            System.out.printf("Symbolic:  %s mode, %d methods, paths %d explored / %d sat / %d unsat, %d tuples, %d tests kept, targets reached %d/%d, %.1f s; bounds loop %d step %d paths %d time %d solver-timeouts %d; cut as exploding %d, unfinished %d, inlined calls %d%n",
                    y.mode(), y.methods(), y.pathsExplored(), y.pathsSatisfiable(), y.pathsUnsat(), y.tuples(), y.testsKept(), y.targetsReached(), y.targetsTotal(),
                    y.millis() / 1000.0, y.loopBoundHits(), y.stepBoundHits(), y.pathBoundHits(), y.timeBudgetHits(), y.solverTimeouts(),
                    y.explosions(), y.unfinished(), y.inlinedCalls());
            if (y.solver() != null) {
                var v = y.solver();
                // the calls with a conversion are counted by the rewrite itself, so there is no count when it is off
                System.out.printf("           solver: %d calls, %.1f s, %d timeouts; %s%n", v.calls(), v.millis() / 1000.0, v.timeouts(),
                        v.bridgeLift() ? "bridge lift on: " + v.callsWithBridge() + " calls with int/string conversions, " + v.assertionsLifted() + " assertions restated"
                                : "bridge lift off");
            }
            if (y.receivers() != null && (y.receivers().paths() > 0 || y.receivers().withStatics() > 0)) {
                var v = y.receivers();
                System.out.printf("           receivers: %d methods with %d candidates; paths reading receiver fields %d, fitted to a candidate %d, no candidate fits %d; paths solved with static values %d; call sequences explored %d, inputs from them %d%n",
                        v.methods(), v.candidates(), v.paths(), v.fitted(), v.unfitted(), v.withStatics(), v.sequences(), v.sequenceTuples());
            }
            if (y.regex() != null) {
                var x = y.regex();
                System.out.printf("           regex channel: %d methods, %d string paths, %d path tuples + %d pattern tuples, %d tests kept, targets reached %d, %.1f s%n",
                        x.methods(), x.stringPaths(), x.tuples(), x.patternTuples(), x.testsKept(), x.targetsReached(), x.millis() / 1000.0);
            }
            if (!y.targetsByCauseOrEmpty().isEmpty()) {
                StringBuilder by = new StringBuilder();
                y.targetsByCauseOrEmpty().forEach((k, v) -> by.append(by.length() > 0 ? ", " : "").append(k).append(' ').append(y.reachedByCauseOrEmpty().getOrDefault(k, 0)).append('/').append(v));
                System.out.println("           targets reached by dependency: " + by);
            }
        }
        if (r.llm() != null) {
            var l = r.llm();
            System.out.printf("LLM:       %d calls, %d cache hits, %d failures %s; %d methods augmented; %.1f s in calls (avg %d ms, max %d ms); tokens in %d / out %d%n",
                    l.calls(), l.cacheHits(), l.failures(), l.failureReasons(), l.methodsAugmented(), l.wallMillis() / 1000.0, l.averageMillis(), l.maxMillis(), l.inputTokens(), l.outputTokens());
        }
        for (String w : r.warnings()) System.out.println("Warning:   " + w);
        System.out.println("Report:    " + Path.of(r.runDir()).resolve("reports").resolve("report.html"));
    }
}
