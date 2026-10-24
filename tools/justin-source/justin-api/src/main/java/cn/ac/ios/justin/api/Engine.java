package cn.ac.ios.justin.api;

import cn.ac.ios.justin.combine.CombineResult;
import cn.ac.ios.justin.combine.Combiner;
import cn.ac.ios.justin.combine.IpogCombiner;
import cn.ac.ios.justin.combine.RelationExpander;
import cn.ac.ios.justin.core.test.Relation;
import cn.ac.ios.justin.strategies.StaticSeedStrategy;
import cn.ac.ios.justin.symexec.SymConfig;
import cn.ac.ios.justin.symexec.SymResult;
import cn.ac.ios.justin.symexec.SymbolicExecutor;
import cn.ac.ios.justin.symexec.SymbolicStrategy;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.config.GenerationConfig;
import cn.ac.ios.justin.core.scope.Scope;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.analysis.FactExtractor;
import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.test.BodyFacts;
import cn.ac.ios.justin.core.test.Outcome;
import cn.ac.ios.justin.core.test.Statement;
import cn.ac.ios.justin.core.test.TestCase;
import cn.ac.ios.justin.core.test.Verdict;
import cn.ac.ios.justin.oracle.ExceptionClassifier;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.ValuePool;
import cn.ac.ios.justin.emit.TestClassEmitter;
import cn.ac.ios.justin.exec.CoverageProbe;
import cn.ac.ios.justin.exec.Invoker;
import cn.ac.ios.justin.exec.Sandbox;
import cn.ac.ios.justin.exec.UncoveredTargets;
import cn.ac.ios.justin.generics.TypeInstantiator;
import cn.ac.ios.justin.llm.AnnotationAugmenter;
import cn.ac.ios.justin.llm.AnnotationInferrer;
import cn.ac.ios.justin.llm.AnthropicLlmClient;
import cn.ac.ios.justin.llm.LlmCache;
import cn.ac.ios.justin.llm.LlmConfig;
import cn.ac.ios.justin.oracle.OracleBuilder;
import cn.ac.ios.justin.runner.CoverageAnalyzer;
import cn.ac.ios.justin.runner.ForkedTestRunner;
import cn.ac.ios.justin.runner.JUnitXmlParser;
import cn.ac.ios.justin.runner.TestCompiler;
import cn.ac.ios.justin.runner.TestPruner;
import cn.ac.ios.justin.runner.ToolJars;
import cn.ac.ios.justin.scan.ClassPathScanner;
import cn.ac.ios.justin.strategies.GenerationContext;
import cn.ac.ios.justin.strategies.AnnotationStrategy;
import cn.ac.ios.justin.strategies.RandomStrategy;
import cn.ac.ios.justin.strategies.SpecialValueStrategy;
import cn.ac.ios.justin.strategies.SpecialValues;
import cn.ac.ios.justin.strategies.Strategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.palantir.javapoet.JavaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The P0 pipeline: scan, generate per class (strategies, combine, execute, oracle), emit, and
 * optionally compile, run in a forked JVM and measure coverage. Front-ends call only this class.
 */
public final class Engine {
    private static final Logger log = LoggerFactory.getLogger(Engine.class);

    /** Subject classpath of the current run, used to open fresh sandboxes for state-dependency checks. */
    private List<Path> freshClasspath = List.of();
    /**
     * Per-class working state. Classes may be generated on several threads (--threads), so the probe
     * runtime of the class in progress and its pending symbolic sessions live in a thread-local.
     */
    private static final class ClassState {
        CoverageProbe probe;
        final List<SymJob> pendingSym = new ArrayList<>();
    }
    private final ThreadLocal<ClassState> state = ThreadLocal.withInitial(ClassState::new);
    private SymbolicStrategy symbolic;
    private SymConfig symConfig;
    private final Map<MethodTarget, Set<String>> calleeKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private SymStats symStats = new SymStats();
    private final java.util.concurrent.atomic.AtomicInteger suspiciousSetupFailures = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, byte[]> classBytesCache = new java.util.concurrent.ConcurrentHashMap<>();

    public GenerationResult run(GenerationRequest req, GenerationListener listener) throws IOException, InterruptedException {
        long tStart = System.currentTimeMillis();
        ProjectModel project = req.project();
        this.freshClasspath = project.fullClasspath();
        GenerationConfig config = req.config();
        List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> rejected = java.util.Collections.synchronizedMap(new TreeMap<>());
        Path runDir = req.runDir();
        Files.createDirectories(runDir);
        Files.writeString(req.outputDir().resolve("latest.txt"), runDir.getFileName().toString());
        log.info("run directory {}", runDir);

        // ---- scan ----
        listener.phase("scan");
        long t0 = System.currentTimeMillis();
        List<ClassTarget> all = ClassPathScanner.scan(project.targetRoots(), n -> true);
        ScanIndex index = new ScanIndex(all);
        Set<String> stateful = new HashSet<>();
        for (ClassTarget ct : all) if (hasMutableStatics(ct)) stateful.add(outermost(ct.binaryName()));
        statefulOuters = stateful;
        List<ClassTarget> targets = all.stream().filter(req.scope()::accepts).filter(Engine::isTestable).toList();
        long scanMillis = System.currentTimeMillis() - t0;
        log.info("scanned {} classes, {} in scope", all.size(), targets.size());
        if (targets.isEmpty()) warnings.add("no classes matched scope " + describe(req.scope()));

        // ---- generate ----
        listener.phase("generate");
        t0 = System.currentTimeMillis();
        List<Strategy> strategies = strategies(config);
        // body facts for exception classification: SootUp on demand, one loader for the run
        FactExtractor facts = null;
        SootUpLoader sootLoader = null;
        try {
            sootLoader = SootUpLoader.minimal(project.targetRoots(), project.dependencyClasspath());
            facts = new FactExtractor(sootLoader);
        } catch (RuntimeException e) {
            warnings.add("SootUp unavailable, exception verdicts without body facts: " + e);
        }
        final FactExtractor factExtractor = facts;
        symStats = new SymStats();
        state.get().pendingSym.clear();
        calleeKeys.clear();
        classBytesCache.clear();
        symConfig = req.symbolic();
        symbolic = req.symbolic() != null && sootLoader != null ? new SymbolicStrategy(sootLoader, req.symbolic()) : null;
        if (symbolic != null) log.info("symbolic execution: {} mode, {} ms and {} paths per method", symConfig.mode(), symConfig.methodTimeBudgetMs(), symConfig.maxPathsPerMethod());
        // optional model-inferred annotations: only when a config file is given, never by default
        AnnotationAugmenter augmenter = null;
        AnthropicLlmClient llmClient = null;
        String llmEndpoint = null;
        if (req.llmConfig() != null) {
            try {
                LlmConfig lc = LlmConfig.load(req.llmConfig(), runDir.getParent().resolve("llm-cache"));
                llmClient = new AnthropicLlmClient(lc);
                llmEndpoint = lc.describe();
                augmenter = new AnnotationAugmenter(new AnnotationInferrer(llmClient, new LlmCache(lc.cacheDir()), lc), project.sourceRoots()).withClassResolver(index::lookup);
                log.warn("LLM annotation inference enabled via {}: method signatures, body facts and source snippets are sent to an external service", lc.describe());
            } catch (IOException | RuntimeException e) {
                warnings.add("LLM annotation inference disabled: " + e.getMessage());
            }
        }
        final AnnotationAugmenter llm = augmenter;
        Combiner combiner = new IpogCombiner(config.strength(), config.maxDomain());
        long[] nway = new long[2]; // covered, total interactions across all methods
        OracleBuilder oracle = new OracleBuilder(project.javaVersion(), index::lookup);
        // tests are emitted right after their class is generated, so TestCase objects never accumulate
        TestClassEmitter emitter = new TestClassEmitter(Math.max(1, config.invocationTimeoutMillis() / 1000 * 5));
        Path srcDir = req.generatedSrcDir(runDir);
        Files.createDirectories(srcDir);
        List<GenerationResult.ClassResult> classResults = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Kept> kept = new ArrayList<>();
        Map<String, Integer> byStrategy = new TreeMap<>();
        Set<String> subjectNames = new HashSet<>();
        for (ClassTarget c : all) subjectNames.add(c.binaryName());
        Map<String, Integer> probeTotals = new HashMap<>();
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong emitMillis = new java.util.concurrent.atomic.AtomicLong();
        Object emitLock = new Object();
        final List<GenerationResult.ClassResult> results = classResults;
        java.util.function.Consumer<ClassTarget> processClass = c -> {
            listener.classStarted(c.binaryName(), idx.incrementAndGet(), targets.size());
            Map<TestCase, Map<String, BitSet>> coverageOf = new IdentityHashMap<>();
            List<TestCase> cases = new ArrayList<>();
            int[] counts = new int[2];
            List<GenerationResult.SymMethod> symMethodsOfClass = List.of();
            CoverageProbe probe = new CoverageProbe();
            state.get().probe = probe;
            try (Sandbox sandbox = new Sandbox(project.fullClasspath(), probe, subjectNames::contains, config.blockIo());
                 Invoker invoker = new Invoker(sandbox, config.invocationTimeoutMillis())) {
                // facts first for every eligible method, so model inference for the whole class can run in parallel
                Map<MethodTarget, BodyFacts> factsOf = new LinkedHashMap<>();
                for (MethodTarget m : c.methods()) {
                    if (!isEligible(m, c, config, req.scope())) continue;
                    BodyFacts bf = BodyFacts.NONE;
                    if (factExtractor != null) {
                        try { bf = factExtractor.extract(m).toBodyFacts(); } catch (RuntimeException ex) { log.debug("facts failed for {}", m.id(), ex); }
                    }
                    factsOf.put(m, bf);
                }
                Map<MethodTarget, MethodTarget> augmentedOf = llm != null ? llm.augmentAll(c, factsOf) : Map.of();
                for (MethodTarget m : factsOf.keySet()) {
                    counts[0]++;
                    int before = cases.size();
                    try {
                        BodyFacts bf = factsOf.get(m);
                        MethodTarget mm = augmentedOf.getOrDefault(m, m);
                        generateForMethod(c, mm, index, config, strategies, combiner, invoker, oracle, cases, rejected, listener, nway, coverageOf, bf);
                    } catch (RuntimeException | StackOverflowError e) {
                        warnings.add("method " + m.display() + " skipped: " + e);
                        log.warn("method {} skipped", m.id(), e);
                        rejected.merge("generator-error", 1, Integer::sum);
                    }
                    if (cases.size() > before) counts[1]++;
                }
                if (!state.get().pendingSym.isEmpty()) {
                    List<SymJob> done = runSymbolicRounds(c, config, strategies, invoker, oracle, cases, rejected, coverageOf);
                    for (SymJob j : done) if (!j.hadTests && j.kept > 0) counts[1]++;
                    symMethodsOfClass = done.stream().map(SymJob::report).toList();
                }
            } catch (RuntimeException e) {
                warnings.add("class " + c.binaryName() + " failed: " + e);
                log.warn("class {} failed", c.binaryName(), e);
            } finally {
                synchronized (probeTotals) { probe.probeTotals().forEach((k, v) -> probeTotals.merge(k, v, Math::max)); }
                probe.shutdown();
                closePendingSym();
            }
            if (config.minimize()) {
                int before = cases.size();
                cases = minimize(cases, coverageOf);
                if (cases.size() < before) rejected.merge("redundant-coverage", before - cases.size(), Integer::sum);
            }
            // emission and result bookkeeping touch shared lists: one class at a time
            synchronized (emitLock) {
            try {
            // emit now and keep only lightweight records of what survived
            long tEmit = System.currentTimeMillis();
            if (cases.isEmpty()) {
                results.add(new GenerationResult.ClassResult(c.binaryName(), null, null, counts[0], counts[1], List.of(), List.of()));
            } else {
                JavaFile file = emitter.emit(c, cases);
                Path written = emitter.write(file, srcDir);
                sources.add(written);
                List<GenerationResult.TestInfo> infos = cases.stream().map(tc -> new GenerationResult.TestInfo(
                        tc.name(), tc.target().display(), tc.strategy(), tc.input().source(), tc.assertionCount(), outcomeKind(tc.outcome()),
                        tc.verdict() == null ? null : tc.verdict().kind().name(), tc.verdict() == null ? null : tc.verdict().evidence(), null, null)).toList();
                results.add(new GenerationResult.ClassResult(c.binaryName(), c.packageName() + "." + TestClassEmitter.testClassName(c), written,
                        counts[0], counts[1], infos, methodSummaries(infos)));
                for (TestCase tc : cases) {
                    byStrategy.merge(tc.strategy(), 1, Integer::sum);
                    kept.add(new Kept(tc.input().strategies(), coverageOf.getOrDefault(tc, Map.of())));
                }
            }
            emitMillis.addAndGet(System.currentTimeMillis() - tEmit);
            // what the kept tests leave uncovered in this class, for the report and for directed strategies
            try {
                BitSet union = null;
                for (TestCase tc : cases) {
                    BitSet b = coverageOf.getOrDefault(tc, Map.of()).get(c.binaryName());
                    if (b == null) continue;
                    if (union == null) union = new BitSet();
                    union.or(b);
                }
                byte[] bytes = classBytes(project.targetRoots(), c.binaryName());
                if (bytes != null) {
                    UncoveredTargets.ClassSummary us = UncoveredTargets.analyze(c.binaryName(), bytes, union, probeTotals.getOrDefault(c.binaryName(), 0), 200);
                    List<GenerationResult.Uncovered> u = us.targets().stream().map(t -> new GenerationResult.Uncovered(
                            t.method() + t.descriptor(), t.line(), t.missedBranches(), t.totalBranches(), t.lineUncovered())).toList();
                    int last = results.size() - 1;
                    results.set(last, results.get(last).withUncovered(us.coveredBranches(), us.totalBranches(), u));
                }
            } catch (RuntimeException | IOException e) {
                log.debug("uncovered-target analysis failed for {}", c.binaryName(), e);
            }
            if (!symMethodsOfClass.isEmpty()) {
                int last = results.size() - 1;
                results.set(last, results.get(last).withSymbolic(symMethodsOfClass));
            }
            } catch (IOException e) {
                warnings.add("emit failed for " + c.binaryName() + ": " + e);
                log.warn("emit failed for {}", c.binaryName(), e);
            }
            }
            coverageOf.clear();
            listener.classFinished(c.binaryName(), cases.size());
        };
        int threads = Math.max(1, req.threads());
        if (threads <= 1) {
            for (ClassTarget c : targets) {
                if (listener.isCancelled()) break;
                processClass.accept(c);
            }
        } else {
            // class-level parallelism: every class has its own sandbox, probe runtime and symbolic sessions
            log.info("generating with {} threads", threads);
            long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            if (maxHeapMb < 1024L * threads) {
                String w = "max heap " + maxHeapMb + " MB for " + threads + " threads: subjects that allocate aggressively can exhaust memory on another thread's"
                        + " invocation; consider -Xmx" + threads + "g or fewer threads";
                warnings.add(w);
                log.warn(w);
            }
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "justin-class"); t.setDaemon(true); return t; });
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (ClassTarget c : targets) futures.add(pool.submit(() -> { if (!listener.isCancelled()) processClass.accept(c); }));
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (java.util.concurrent.ExecutionException e) { warnings.add("class job failed: " + e.getCause()); log.warn("class job failed", e.getCause()); }
            }
            pool.shutdownNow();
            classResults.sort(java.util.Comparator.comparing(GenerationResult.ClassResult::binaryName));
        }
        long generateMillis = System.currentTimeMillis() - t0 - emitMillis.get();
        GenerationResult.LlmSummary llmSummary = null;
        if (llm != null) {
            AnnotationInferrer inf = llm.inferrer();
            var u = llmClient.usage();
            llmSummary = new GenerationResult.LlmSummary(llmEndpoint, inf.calls(), inf.cacheHits(), inf.failures(), inf.failureReasons(),
                    llm.augmentedMethods(), u.millis(), u.averageMillis(), u.maxMillis(), u.inputTokens(), u.outputTokens());
            llm.close();
        }
        GenerationResult.ProbeCoverage probeCoverage = probeCoverage(kept, probeTotals);

        // ---- compile / run / coverage ----
        GenerationResult.CompileSummary compile = null;
        GenerationResult.ExecutionSummary execution = null;
        CoverageAnalyzer.Report coverage = null;
        long compileMillis = 0, runMillis = 0, coverageMillis = 0;
        if (req.runTests() && !sources.isEmpty()) {
            listener.phase("compile");
            t0 = System.currentTimeMillis();
            List<Path> cp = new ArrayList<>(project.fullClasspath());
            cp.add(ToolJars.junitApiForCompilation());
            TestCompiler compiler = new TestCompiler(Math.min(17, Math.max(8, project.javaVersion())));
            List<String> pruned = new ArrayList<>();
            TestCompiler.Result cr = compileWithPruning(compiler, sources, cp, req.generatedClassesDir(runDir), rejected, warnings, pruned);
            Map<String, List<String>> failures = new LinkedHashMap<>();
            cr.failed().forEach((p, msgs) -> failures.put(p.toString(), msgs));
            compile = new GenerationResult.CompileSummary(cr.compiled().size(), cr.failed().size(), failures, pruned);
            compileMillis = System.currentTimeMillis() - t0;
            if (!cr.failed().isEmpty()) warnings.add(cr.failed().size() + " generated file(s) failed to compile; see report");

            if (!cr.compiled().isEmpty()) {
                listener.phase("run");
                t0 = System.currentTimeMillis();
                Path reports = req.reportsDir(runDir);
                Path junitDir = reports.resolve("junit");
                Path exec = reports.resolve("jacoco.exec");
                List<String> includes = targets.stream().map(c -> c.binaryName().replace('$', '.') + "*").distinct().toList();
                ForkedTestRunner.Result rr = new ForkedTestRunner().run(new ForkedTestRunner.Spec(
                        req.generatedClassesDir(runDir), project.fullClasspath(), junitDir, exec,
                        List.of(packagePattern(targets)), Math.max(60_000, config.classTimeBudgetMillis() * Math.max(1, targets.size())),
                        reports.resolve("junit-console.log")));
                JUnitXmlParser.Summary summary = JUnitXmlParser.parse(junitDir);
                List<JUnitXmlParser.TestResult> failed = summary.tests().stream()
                        .filter(t -> t.status() == JUnitXmlParser.Status.FAILED || t.status() == JUnitXmlParser.Status.ERROR).toList();
                // a failure that passes alone is an order dependency (static state polluted by other tests):
                // such tests are removed from the sources rather than shipped as flaky
                List<String> orderDependent = new ArrayList<>();
                if (!failed.isEmpty() && !rr.timedOut()) {
                    listener.phase("isolate");
                    List<JUnitXmlParser.TestResult> realFailures = new ArrayList<>();
                    int budget = 0;
                    for (JUnitXmlParser.TestResult f : failed) {
                        String key = f.className() + "#" + f.methodName();
                        if (budget++ >= 50) { realFailures.add(f); continue; }
                        Path isoDir = reports.resolve("junit-isolation").resolve(f.methodName());
                        new ForkedTestRunner().run(new ForkedTestRunner.Spec(req.generatedClassesDir(runDir), project.fullClasspath(), isoDir, null,
                                List.of(), 120_000, reports.resolve("junit-isolation.log"), List.of(key)));
                        JUnitXmlParser.Summary alone = JUnitXmlParser.parse(isoDir);
                        boolean passesAlone = alone.total() > 0 && alone.count(JUnitXmlParser.Status.PASSED) == alone.total();
                        if (passesAlone) orderDependent.add(key); else realFailures.add(f);
                    }
                    if (!orderDependent.isEmpty()) {
                        removeOrderDependent(orderDependent, classResults, compiler, cp, req.generatedClassesDir(runDir), warnings);
                        rejected.merge("order-dependent", orderDependent.size(), Integer::sum);
                    }
                    failed = realFailures;
                }
                execution = new GenerationResult.ExecutionSummary(summary.total(), summary.count(JUnitXmlParser.Status.PASSED),
                        failed.stream().filter(t -> t.status() == JUnitXmlParser.Status.FAILED).count(),
                        failed.stream().filter(t -> t.status() == JUnitXmlParser.Status.ERROR).count(),
                        summary.count(JUnitXmlParser.Status.SKIPPED), rr.timedOut(), failed, orderDependent);
                runMillis = System.currentTimeMillis() - t0;
                if (rr.timedOut()) warnings.add("test JVM timed out");
                classResults = joinExecution(classResults, summary);
                if (!orderDependent.isEmpty()) classResults = dropTests(classResults, orderDependent);

                listener.phase("coverage");
                t0 = System.currentTimeMillis();
                Set<String> inScope = new HashSet<>();
                for (ClassTarget c : targets) inScope.add(c.binaryName());
                coverage = CoverageAnalyzer.analyze(exec, project.targetRoots(), inScope::contains, reports.resolve("coverage-html"), project.sourceRoots());
                coverageMillis = System.currentTimeMillis() - t0;
            }
        }

        GenerationResult result = new GenerationResult(project.name(), describe(req.scope()), config.seed(), runDir.toString(),
                config.strategies(), byStrategy, new GenerationResult.NwayCoverage(config.strength(), nway[1], nway[0]), probeCoverage,
                verdictSummary(classResults), llmSummary, symbolicSummary(), classResults, rejected,
                compile, execution, coverage,
                new GenerationResult.Timings(scanMillis, generateMillis, emitMillis.get(), compileMillis, runMillis, coverageMillis, System.currentTimeMillis() - tStart,
                        llmSummary == null ? 0 : llmSummary.wallMillis()),
                warnings);
        writeJson(result, req.reportsDir(runDir).resolve("report.json"));
        HtmlReportWriter.write(result, req.reportsDir(runDir).resolve("report.html"));
        return result;
    }

    // ---------------------------------------------------------------------------------------------

    /** Per-method counts from the test infos of one class (execution columns are filled by joinExecution). */
    static List<GenerationResult.MethodSummary> methodSummaries(List<GenerationResult.TestInfo> infos) {
        Map<String, List<GenerationResult.TestInfo>> byMethod = new LinkedHashMap<>();
        for (GenerationResult.TestInfo t : infos) byMethod.computeIfAbsent(t.targetMethod(), k -> new ArrayList<>()).add(t);
        List<GenerationResult.MethodSummary> out = new ArrayList<>();
        for (var e : byMethod.entrySet()) {
            List<GenerationResult.TestInfo> ts = e.getValue();
            int ex = (int) ts.stream().filter(t -> t.outcomeKind().startsWith("threw")).count();
            out.add(new GenerationResult.MethodSummary(e.getKey(), ts.size(), ts.size() - ex, ex,
                    (int) ts.stream().filter(t -> "EXPECTED".equals(t.verdict())).count(),
                    (int) ts.stream().filter(t -> "SUSPECT".equals(t.verdict())).count(),
                    (int) ts.stream().filter(t -> "UNKNOWN".equals(t.verdict())).count(),
                    ts.stream().filter(t -> "PASSED".equals(t.executionStatus())).count(),
                    ts.stream().filter(t -> t.executionStatus() != null && !"PASSED".equals(t.executionStatus()) && !"SKIPPED".equals(t.executionStatus())).count()));
        }
        return out;
    }

    /** Prunes order-dependent tests from their source files and recompiles those files. */
    private static void removeOrderDependent(List<String> keys, List<GenerationResult.ClassResult> classes, TestCompiler compiler,
                                             List<Path> cp, Path outDir, List<String> warnings) throws IOException {
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        for (String k : keys) byClass.computeIfAbsent(k.substring(0, k.indexOf('#')), x -> new ArrayList<>()).add(k.substring(k.indexOf('#') + 1));
        List<Path> touched = new ArrayList<>();
        for (GenerationResult.ClassResult c : classes) {
            List<String> names = byClass.get(c.testClass());
            if (names == null || c.sourceFile() == null) continue;
            TestPruner.Result pr = TestPruner.pruneByName(c.sourceFile(), names);
            if (!pr.fileDeleted() && pr.removedMethods() > 0) touched.add(c.sourceFile());
            log.info("removed {} order-dependent test(s) from {}", pr.removedMethods(), c.sourceFile().getFileName());
        }
        if (!touched.isEmpty()) {
            TestCompiler.Result cr = compiler.compile(touched, cp, outDir);
            if (!cr.failed().isEmpty()) warnings.add("recompilation after removing order-dependent tests failed for " + cr.failed().size() + " file(s)");
        }
    }

    private static List<GenerationResult.ClassResult> dropTests(List<GenerationResult.ClassResult> classes, List<String> keys) {
        Set<String> drop = new HashSet<>(keys);
        List<GenerationResult.ClassResult> out = new ArrayList<>();
        for (GenerationResult.ClassResult c : classes) {
            if (c.testClass() == null) { out.add(c); continue; }
            List<GenerationResult.TestInfo> keep = c.tests().stream().filter(t -> !drop.contains(c.testClass() + "#" + t.name())).toList();
            out.add(keep.size() == c.tests().size() ? c : c.withTests(keep, methodSummaries(keep)));
        }
        return out;
    }

    /** Attaches the forked run's status and failure details to every test info. */
    static List<GenerationResult.ClassResult> joinExecution(List<GenerationResult.ClassResult> classes, JUnitXmlParser.Summary summary) {
        Map<String, JUnitXmlParser.TestResult> byKey = new HashMap<>();
        for (JUnitXmlParser.TestResult t : summary.tests()) byKey.put(t.className() + "#" + t.methodName(), t);
        List<GenerationResult.ClassResult> out = new ArrayList<>();
        for (GenerationResult.ClassResult c : classes) {
            if (c.testClass() == null) { out.add(c); continue; }
            List<GenerationResult.TestInfo> infos = new ArrayList<>();
            for (GenerationResult.TestInfo t : c.tests()) {
                JUnitXmlParser.TestResult r = byKey.get(c.testClass() + "#" + t.name());
                if (r == null) infos.add(t.withExecution("NOT_RUN", null));
                else infos.add(t.withExecution(r.status().name(), r.status() == JUnitXmlParser.Status.PASSED || r.status() == JUnitXmlParser.Status.SKIPPED
                        ? null : new GenerationResult.Failure(r.exceptionType(), r.message(), r.stackTrace())));
            }
            out.add(c.withTests(infos, methodSummaries(infos)));
        }
        return out;
    }

    static GenerationResult.VerdictSummary verdictSummary(List<GenerationResult.ClassResult> classes) {
        int expected = 0, suspect = 0, unknown = 0;
        List<GenerationResult.Suspect> suspects = new ArrayList<>();
        for (GenerationResult.ClassResult c : classes) {
            for (GenerationResult.TestInfo t : c.tests()) {
                if (t.verdict() == null) continue;
                switch (t.verdict()) {
                    case "EXPECTED" -> expected++;
                    case "SUSPECT" -> {
                        suspect++;
                        String ex = t.outcomeKind().startsWith("threw:") ? t.outcomeKind().substring(6) : t.outcomeKind();
                        suspects.add(new GenerationResult.Suspect(c.testClass(), t.name(), t.targetMethod(), ex, t.evidence(), t.strategy(), t.source()));
                    }
                    default -> unknown++;
                }
            }
        }
        return new GenerationResult.VerdictSummary(expected, suspect, unknown, suspects);
    }

    /** Reads a class file from the target roots (directories or jars); null if not found. */
    static byte[] classBytes(List<Path> roots, String binaryName) throws IOException {
        String rel = binaryName.replace('.', '/') + ".class";
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                Path p = root.resolve(rel);
                if (Files.exists(p)) return Files.readAllBytes(p);
            } else if (Files.isRegularFile(root)) {
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(root.toFile())) {
                    java.util.jar.JarEntry e = jf.getJarEntry(rel);
                    if (e != null) try (java.io.InputStream in = jf.getInputStream(e)) { return in.readAllBytes(); }
                }
            }
        }
        return null;
    }

    private static final Set<String> MUTABLE_CONTAINERS = Set.of(
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.concurrent.ConcurrentHashMap",
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList", "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.Collection", "java.util.Deque", "java.util.Queue", "java.lang.StringBuilder", "java.lang.StringBuffer",
            "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicReference", "java.lang.ThreadLocal");

    /**
     * Top-level classes that keep mutable static state, in themselves or in a nested class. An interface such as
     * jline's SystemRegistry has no field at all: its static get() reads a map held by a nested class.
     */
    private volatile Set<String> statefulOuters = Set.of();

    static String outermost(String binaryName) {
        int i = binaryName.indexOf('$');
        return i < 0 ? binaryName : binaryName.substring(0, i);
    }

    /**
     * Static non-final fields, static final fields holding mutable containers or arrays, or a static final
     * instance of the class itself (a singleton) whose own fields can change.
     */
    static boolean hasMutableStatics(ClassTarget c) {
        for (var f : c.fields()) {
            if (!f.isStatic()) continue;
            if (!f.isFinal()) return true;
            String t = f.type().erasureName();
            if (t.endsWith("[]") || MUTABLE_CONTAINERS.contains(t)) return true;
            if (t.equals(c.binaryName())) {
                for (var g : c.fields()) {
                    if (g.isStatic()) continue;
                    String gt = g.type().erasureName();
                    if (!g.isFinal() || gt.endsWith("[]") || MUTABLE_CONTAINERS.contains(gt)) return true;
                }
            }
        }
        return false;
    }

    /** Greedy set cover in generation order: a test survives only if it hits a probe no earlier survivor hit. */
    static List<TestCase> minimize(List<TestCase> cases, Map<TestCase, Map<String, BitSet>> coverageOf) {
        Map<String, BitSet> union = new HashMap<>();
        Set<String> emptySignatures = new HashSet<>();
        List<TestCase> kept = new ArrayList<>();
        for (TestCase tc : cases) {
            Map<String, BitSet> cov = coverageOf.getOrDefault(tc, Map.of());
            if (cov.isEmpty()) {
                // an exception before the first probe leaves no trace: keep one such test per method and exception
                String sig = tc.target().id() + "|" + outcomeKind(tc.outcome());
                if (emptySignatures.add(sig)) kept.add(tc);
                continue;
            }
            boolean adds = false;
            for (var e : cov.entrySet()) {
                BitSet seen = union.computeIfAbsent(e.getKey(), k -> new BitSet());
                BitSet fresh = (BitSet) e.getValue().clone();
                fresh.andNot(seen);
                if (!fresh.isEmpty()) { adds = true; seen.or(e.getValue()); }
            }
            if (adds) kept.add(tc);
        }
        return kept;
    }

    /** What survives of a kept test after emission: which strategies produced it and which probes it hit. */
    record Kept(Set<String> strategies, Map<String, BitSet> coverage) {}

    /** Probe-level coverage of the kept tests, overall and per strategy (exclusive = hit only by tests using that strategy). */
    static GenerationResult.ProbeCoverage probeCoverage(List<Kept> kept, Map<String, Integer> totals) {
        Map<String, BitSet> all = new HashMap<>();
        Map<String, Map<String, BitSet>> byStrategy = new TreeMap<>();
        Map<String, Integer> testsByStrategy = new TreeMap<>();
        for (Kept k : kept) {
            k.coverage().forEach((c, v) -> all.computeIfAbsent(c, x -> new BitSet()).or(v));
            for (String s : k.strategies()) {
                testsByStrategy.merge(s, 1, Integer::sum);
                Map<String, BitSet> m = byStrategy.computeIfAbsent(s, x -> new HashMap<>());
                k.coverage().forEach((c, v) -> m.computeIfAbsent(c, x -> new BitSet()).or(v));
            }
        }
        long covered = all.values().stream().mapToLong(BitSet::cardinality).sum();
        long total = 0;
        for (var e : totals.entrySet()) total += e.getValue();
        Map<String, GenerationResult.StrategyStats> stats = new TreeMap<>();
        for (var e : byStrategy.entrySet()) {
            String s = e.getKey();
            long hit = e.getValue().values().stream().mapToLong(BitSet::cardinality).sum();
            // probes hit by tests that did not use s
            Map<String, BitSet> others = new HashMap<>();
            for (Kept k : kept) {
                if (k.strategies().contains(s)) continue;
                k.coverage().forEach((c, v) -> others.computeIfAbsent(c, x -> new BitSet()).or(v));
            }
            long exclusive = 0;
            for (var c : e.getValue().entrySet()) {
                BitSet ex = (BitSet) c.getValue().clone();
                BitSet o = others.get(c.getKey());
                if (o != null) ex.andNot(o);
                exclusive += ex.cardinality();
            }
            stats.put(s, new GenerationResult.StrategyStats(testsByStrategy.getOrDefault(s, 0), hit, exclusive));
        }
        return new GenerationResult.ProbeCoverage(total, covered, stats);
    }

    /**
     * Compile; for every file javac rejects, remove just the offending test methods and try again.
     * Files whose errors cannot be attributed to a method are dropped. At most four rounds.
     */
    private static TestCompiler.Result compileWithPruning(TestCompiler compiler, List<Path> sources, List<Path> cp, Path outDir,
                                                          Map<String, Integer> rejected, List<String> warnings, List<String> prunedOut) throws IOException {
        List<Path> remaining = new ArrayList<>(sources);
        Map<Path, List<String>> dropped = new LinkedHashMap<>();
        TestCompiler.Result last = null;
        for (int round = 0; round < 4 && !remaining.isEmpty(); round++) {
            last = compiler.compile(remaining, cp, outDir);
            if (last.failed().isEmpty()) break;
            for (var e : last.failed().entrySet()) {
                Path file = e.getKey();
                List<Integer> lines = TestPruner.errorLines(e.getValue());
                TestPruner.Result pr = lines.isEmpty() ? new TestPruner.Result(0, List.of(), false) : TestPruner.prune(file, lines);
                if (pr.removedMethods() == 0) {
                    dropped.put(file, e.getValue());
                    remaining.remove(file);
                    log.warn("dropping {}: errors outside test methods", file.getFileName());
                } else {
                    rejected.merge("compile-pruned", pr.removedMethods(), Integer::sum);
                    String cls = file.getFileName().toString().replace(".java", "");
                    String why = e.getValue().get(0).replaceAll("^line \\d+: ", "").replace('\n', ' ');
                    for (String n : pr.removedNames()) prunedOut.add(cls + "#" + n + ": " + why);
                    log.info("pruned {} test(s) from {}: {}", pr.removedMethods(), file.getFileName(), e.getValue().get(0));
                    if (pr.fileDeleted()) remaining.remove(file);
                }
            }
        }
        if (last == null) return new TestCompiler.Result(List.of(), Map.of());
        if (!last.failed().isEmpty() && !dropped.keySet().containsAll(last.failed().keySet())) {
            // rounds exhausted with errors still attributed to files: report them as failed
            last.failed().forEach(dropped::putIfAbsent);
            remaining.removeAll(last.failed().keySet());
        }
        if (!dropped.isEmpty()) warnings.add(dropped.size() + " generated file(s) dropped after pruning attempts");
        return new TestCompiler.Result(remaining, dropped);
    }

    private void generateForMethod(ClassTarget c, MethodTarget m, ScanIndex index, GenerationConfig config, List<Strategy> strategies,
                                   Combiner combiner, Invoker invoker, OracleBuilder oracle, List<TestCase> out,
                                   Map<String, Integer> rejected, GenerationListener listener, long[] nway,
                                   Map<TestCase, Map<String, BitSet>> coverageOf, BodyFacts bodyFacts) {
        // one binding environment per method: class-level vars first, then method-level vars
        TypeInstantiator inst = new TypeInstantiator(new Random(config.seed() ^ m.id().hashCode()), new cn.ac.ios.justin.strategies.ProjectTypeKnowledge(index));
        List<TypeRef.TypeVar> declared = new ArrayList<>(c.typeParams());
        declared.addAll(m.typeParams());
        Map<String, TypeRef> bindings = inst.bind(declared);
        // relations from the body plus @IndexOf / @ArrayPara declarations; a parameter is derived by at most one relation
        List<Relation> relations = new ArrayList<>(bodyFacts.relations());
        Set<Integer> dependents = new HashSet<>(RelationExpander.dependents(relations));
        for (var p : m.params()) {
            for (var a : p.annotations()) {
                if (dependents.contains(p.index())) continue;
                String simple = a.simpleName();
                if (simple.equals("IndexOf")) {
                    Object target = a.values().get("value");
                    for (var q : m.params()) {
                        if (target != null && q.name().equals(target.toString()) && q.index() != p.index()) {
                            relations.add(new Relation(Relation.Kind.INDEX_OF, p.index(), q.index(), "@IndexOf"));
                            dependents.add(p.index());
                        }
                    }
                } else if (simple.equals("ArrayPara")) {
                    // JNN @ArrayPara(k, flag): this parameter is the length of parameter k (0), at most it (-1) or at least it (1)
                    int base = a.values().get("paraIndex") instanceof Number n ? n.intValue() : -1;
                    int flag = a.values().get("flag") instanceof Number n ? n.intValue() : 0;
                    if (base >= 0 && base < m.params().size() && base != p.index()) {
                        Relation.Kind kind = flag == 0 ? Relation.Kind.LENGTH_EQ : flag < 0 ? Relation.Kind.LENGTH_LE : Relation.Kind.LENGTH_GE;
                        relations.add(new Relation(kind, p.index(), base, "@ArrayPara"));
                        dependents.add(p.index());
                    }
                }
            }
        }
        GenerationContext ctx = new GenerationContext(config, c, m, index, bindings, bodyFacts.withRelations(relations));

        // contract-derived values first (annotation), then boundaries (special), then random: the
        // each-choice rows are built in candidate order, so the order decides what survives a small budget
        List<Strategy> ordered = new ArrayList<>(strategies);
        ordered.sort((a, b) -> Integer.compare(priority(a), priority(b)));
        List<ValuePool> merged = null;
        for (Strategy s : ordered) {
            List<ValuePool> pools = s.generate(ctx);
            if (merged == null) merged = pools;
            else for (int i = 0; i < pools.size() && i < merged.size(); i++) merged.get(i).addAll(pools.get(i).candidates());
        }
        if (merged != null) merged = constructible(merged, invoker, rejected);
        if (merged == null && symbolic == null) return;
        // ---- stages A/B: value pools -> covering array -> relation-derived tuples ----
        List<InputTuple> tuples = List.of();
        if (merged != null) {
            // dependent parameters take a placeholder in the covering array and are derived afterwards
            List<ValuePool> forCombiner = merged;
            if (!dependents.isEmpty()) {
                forCombiner = new ArrayList<>();
                for (ValuePool p : merged) {
                    if (!dependents.contains(p.slot()) || p.isEmpty()) { forCombiner.add(p); continue; }
                    ValuePool one = new ValuePool(p.slot());
                    one.add(p.candidates().get(0));
                    forCombiner.add(one);
                }
            }
            CombineResult combined = combiner.combine(m, forCombiner, bindings, config.tuplesPerMethod(), config.seed());
            tuples = combined.tuples();
            if (!relations.isEmpty()) {
                tuples = RelationExpander.expand(m, tuples, relations, merged, 3, config.tuplesPerMethod() * 3);
            }
            synchronized (nway) {
                nway[0] += combined.coveredInteractions();
                nway[1] += combined.totalInteractions();
            }
        }
        Set<String> names = new HashSet<>();
        int tests = tuples.isEmpty() ? 0 : processTuples(c, m, tuples, config, strategies, invoker, oracle, out, rejected, coverageOf, bodyFacts, names);
        int total = tuples.size();
        // ---- generic targets: a second set of type arguments (String instead of Integer, another enum or
        //      project type) reaches compareTo/instanceof/equals paths the first binding cannot ----
        if (!declared.isEmpty() && merged != null) {
            Map<String, TypeRef> alt = null;
            for (int k = 1; k <= 3 && alt == null; k++) {
                Map<String, TypeRef> b2 = new TypeInstantiator(new Random((config.seed() ^ m.id().hashCode()) + 7919L * k),
                        new cn.ac.ios.justin.strategies.ProjectTypeKnowledge(index)).bind(declared);
                if (!b2.equals(bindings)) alt = b2;
            }
            if (alt != null) {
                GenerationContext ctx2 = new GenerationContext(config, c, m, index, alt, bodyFacts.withRelations(relations));
                List<ValuePool> merged2 = null;
                for (Strategy s : ordered) {
                    List<ValuePool> pools = s.generate(ctx2);
                    if (merged2 == null) merged2 = pools;
                    else for (int i = 0; i < pools.size() && i < merged2.size(); i++) merged2.get(i).addAll(pools.get(i).candidates());
                }
                if (merged2 != null) {
                    merged2 = constructible(merged2, invoker, rejected);
                    List<ValuePool> forCombiner2 = merged2;
                    if (!dependents.isEmpty()) {
                        forCombiner2 = new ArrayList<>();
                        for (ValuePool p : merged2) {
                            if (!dependents.contains(p.slot()) || p.isEmpty()) { forCombiner2.add(p); continue; }
                            ValuePool one = new ValuePool(p.slot());
                            one.add(p.candidates().get(0));
                            forCombiner2.add(one);
                        }
                    }
                    int budget = Math.max(4, config.tuplesPerMethod() / 2);
                    CombineResult combined2 = combiner.combine(m, forCombiner2, alt, budget, config.seed() + 1);
                    List<InputTuple> tuples2 = combined2.tuples();
                    if (!relations.isEmpty()) tuples2 = RelationExpander.expand(m, tuples2, relations, merged2, 3, budget * 3);
                    if (!tuples2.isEmpty()) {
                        tests += processTuples(c, m, tuples2, config, strategies, invoker, oracle, out, rejected, coverageOf, bodyFacts, names);
                        total += tuples2.size();
                    }
                }
            }
        }
        // ---- symbolic execution: opened here, run in rounds once every method of the class has had
        //      stages A/B (plan 7.3); cascade mode aims at the uncovered lines, direct mode at everything ----
        if (symbolic != null) {
            boolean direct = symConfig.direct();
            Set<Integer> targetLines = uncoveredLinesOf(c, m, out, coverageOf);
            if (log.isDebugEnabled()) log.debug("symbolic {}: {} kept tests so far, uncovered target lines {}", m.id(), out.size(), targetLines);
            if (direct || !targetLines.isEmpty()) {
                Value receiver = null;
                if (!m.isStatic() && !m.isConstructor() && merged != null && !merged.isEmpty() && merged.get(0).slot() == -1 && !merged.get(0).isEmpty()) {
                    receiver = merged.get(0).candidates().get(0).value();
                }
                boolean hadTests = tests > 0;
                // the regex channel first: static and cheap, for targets whose conditions depend on string parameters
                Set<Integer> steer = targetLines;
                int regexReached = 0;
                if (symConfig.regexChannel()) {
                    long t0 = System.currentTimeMillis();
                    SymbolicStrategy.RegexOutcome ro = symbolic.regexTuples(ctx, direct ? Set.of() : targetLines, receiver);
                    List<InputTuple> patternTuples = patternTuples(m, ro, merged, bindings, combiner, config);
                    int keptBefore = out.size();
                    if (!ro.tuples().isEmpty() || !patternTuples.isEmpty()) {
                        List<InputTuple> all = new ArrayList<>(ro.tuples());
                        all.addAll(patternTuples);
                        tests += processTuples(c, m, all, config, strategies, invoker, oracle, out, rejected, coverageOf, bodyFacts, names);
                        total += all.size();
                        if (!targetLines.isEmpty()) {
                            steer = uncoveredLinesOf(c, m, out, coverageOf);
                            for (int l : targetLines) if (!steer.contains(l)) regexReached++;
                        }
                    }
                    synchronized (symStats) {
                        if (ro.stringPaths() > 0) symStats.regexMethods++;
                        symStats.regexPaths += ro.stringPaths();
                        symStats.regexTuples += ro.tuples().size();
                        symStats.regexPatternTuples += patternTuples.size();
                        symStats.regexKept += out.size() - keptBefore;
                        symStats.regexReached += regexReached;
                        symStats.regexMillis += System.currentTimeMillis() - t0;
                    }
                }
                Map<String, List<Value>> roots = objectRoots(m, receiver == null ? null : merged, symConfig.objects().receiverCandidates());
                SymbolicStrategy.Session session = direct || !steer.isEmpty()
                        ? symbolic.open(ctx, direct ? Set.of() : steer, roots, symConfig.objects().receiverCandidates() > 0 ? vs -> new ConcreteReceivers(invoker, vs) : null)
                        : null;
                if (session != null) {
                    // the job reports against the targets as they were before the regex channel, so reach rates stay comparable
                    SymJob job = new SymJob(c, m, session, targetLines, names, bodyFacts, hadTests);
                    job.reached = regexReached;
                    state.get().pendingSym.add(job);
                } else if (regexReached > 0) {
                    synchronized (symStats) {
                        symStats.targetsTotal += targetLines.size();
                        symStats.targetsReached += regexReached;
                        for (var e : symbolic.attribute(m, targetLines).entrySet()) {
                            symStats.targetsByCause.merge(e.getValue(), 1, Integer::sum);
                            if (!steer.contains(e.getKey())) symStats.reachedByCause.merge(e.getValue(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        listener.methodFinished(m.id(), total, tests);
    }

    /**
     * The regexes the channel inferred for a parameter act as a virtual @Pattern on it: strings drawn from
     * them replace that parameter's pool and are combined with a few values of
     * the other parameters, so a string that opens a branch also meets the other arguments it needs.
     */
    private static List<InputTuple> patternTuples(MethodTarget m, SymbolicStrategy.RegexOutcome ro, List<ValuePool> merged, Map<String, TypeRef> bindings,
                                                  Combiner combiner, GenerationConfig config) {
        if (ro.samples().isEmpty() || merged == null || m.params().size() < 2) return List.of();
        List<ValuePool> pools = new ArrayList<>();
        boolean any = false;
        for (ValuePool p : merged) {
            ValuePool q = new ValuePool(p.slot());
            List<String> drawn = p.slot() >= 0 ? ro.samples().get(p.slot()) : null;
            boolean plainString = p.slot() >= 0 && m.params().get(p.slot()).type().erasureName().equals("java.lang.String");
            if (drawn != null && plainString) {
                for (String s : drawn) q.add(cn.ac.ios.justin.core.value.Candidate.legal(new Value.StringValue(s), "regex"));
                any |= !q.isEmpty();
            }
            if (q.isEmpty()) for (int i = 0; i < p.candidates().size() && i < 3; i++) q.add(p.candidates().get(i));
            pools.add(q);
        }
        if (!any) return List.of();
        List<InputTuple> out = new ArrayList<>();
        for (InputTuple t : combiner.combine(m, pools, bindings, Math.min(10, config.tuplesPerMethod()), config.seed() + 2).tuples()) {
            if (!t.strategies().contains("regex")) continue;
            out.add(new InputTuple(m, t.receiver(), t.args(), t.typeBindings(), "regex:pattern", Set.of("regex")));
        }
        return out;
    }

    /**
     * The objects a path can be fitted to, by root: "" the receivers (the default one first, then the other legal
     * candidates of the pool, each once), arg:i the object candidates of the i-th parameter, ctor:i the objects
     * the default receiver is constructed from. pools is null for a static method or a constructor.
     */
    private static Map<String, List<Value>> objectRoots(MethodTarget m, List<ValuePool> pools, int max) {
        Map<String, List<Value>> roots = new java.util.LinkedHashMap<>();
        if (pools == null) return roots;
        for (ValuePool pool : pools) {
            List<Value> out = new ArrayList<>();
            if (pool.slot() == -1 && !pool.isEmpty()) out.add(pool.candidates().get(0).value());
            for (Candidate c : pool.candidates()) {
                if (out.size() >= Math.max(1, max)) break;
                if (c.legal() && c.value() instanceof Value.ObjectValue && !out.contains(c.value())) out.add(c.value());
            }
            if (out.isEmpty()) continue;
            if (pool.slot() == -1) roots.put("", out);
            else if (m.params().get(pool.slot()).type() instanceof TypeRef.ClassRef) roots.put(SymbolicExecutor.ARG + pool.slot(), out);
        }
        List<Value> receivers = roots.get("");
        if (receivers != null && receivers.get(0) instanceof Value.ObjectValue o && o.recipe() instanceof Value.Recipe.ConstructorCall cc) {
            for (int i = 0; i < cc.args().size(); i++) {
                if (cc.args().get(i) instanceof Value.ObjectValue) roots.put(SymbolicExecutor.CTOR + i, List.of(cc.args().get(i)));
            }
        }
        return roots;
    }

    /** A method waiting for its symbolic rounds, and what they yielded. */
    private static final class SymJob {
        final ClassTarget c; final MethodTarget m; final SymbolicStrategy.Session session; final Set<Integer> targets;
        final Set<String> names; final BodyFacts facts; final boolean hadTests;
        long allocated, used;
        int tuples, kept, reached;

        SymJob(ClassTarget c, MethodTarget m, SymbolicStrategy.Session session, Set<Integer> targets, Set<String> names, BodyFacts facts, boolean hadTests) {
            this.c = c; this.m = m; this.session = session; this.targets = targets; this.names = names; this.facts = facts; this.hadTests = hadTests;
        }

        int remainingTargets() { return targets.size() - reached; }

        /** Round-two weight: remaining targets times the gain so far (direct mode: gain only). */
        double weight(boolean direct) { return direct ? 1.0 + kept : (1.0 + remainingTargets()) * (1.0 + kept); }

        GenerationResult.SymMethod report() {
            SymResult.Stats st = session.stats();
            return new GenerationResult.SymMethod(m.display(), allocated, used, st.pathsSatisfiable(), tuples, kept, targets.size(), reached,
                    st.inlinedCalls(), st.stopReason(), st.finished(), st.loopBoundHits(), st.solverTimeouts());
        }
    }

    /**
     * Plan 7.3: round one gives every method a start budget weighted by its branch count; round two hands
     * what is left of the class budget to the methods that still make progress, never beyond the
     * per-method maximum. Sessions are closed at the end; the jobs are returned for the report.
     */
    private List<SymJob> runSymbolicRounds(ClassTarget c, GenerationConfig config, List<Strategy> strategies, Invoker invoker, OracleBuilder oracle,
                                           List<TestCase> out, Map<String, Integer> rejected, Map<TestCase, Map<String, BitSet>> coverageOf) {
        List<SymJob> jobs = new ArrayList<>(state.get().pendingSym);
        state.get().pendingSym.clear();
        long classBudget = symConfig.classTimeBudgetMs();
        long classStart = System.currentTimeMillis();
        boolean direct = symConfig.direct();
        // round 1: a start budget for everyone, split by branch count within the class budget
        int wsum = 0;
        for (SymJob j : jobs) wsum += j.session.complexity();
        long round1 = Math.min(classBudget, jobs.size() * symConfig.methodStartBudgetMs());
        for (SymJob j : jobs) {
            long share = wsum == 0 ? round1 / jobs.size() : round1 * j.session.complexity() / wsum;
            long budget = Math.max(100, Math.min(symConfig.methodStartBudgetMs(), share));
            runSymbolicJob(c, j, budget, config, strategies, invoker, oracle, out, rejected, coverageOf);
        }
        // round 2: the rest of the class budget to the unfinished methods that still have targets (or all, in direct mode)
        long remaining = classBudget - (System.currentTimeMillis() - classStart);
        List<SymJob> open = jobs.stream().filter(j -> !j.session.finished() && (direct || j.remainingTargets() > 0)).toList();
        if (remaining > 200 && !open.isEmpty()) {
            double wsum2 = 0;
            for (SymJob j : open) wsum2 += j.weight(direct);
            for (SymJob j : open) {
                long share = (long) (remaining * j.weight(direct) / wsum2);
                long budget = Math.min(share, symConfig.methodTimeBudgetMs() - j.used);
                if (budget < 100) continue;
                runSymbolicJob(c, j, budget, config, strategies, invoker, oracle, out, rejected, coverageOf);
            }
        }
        synchronized (symStats) {
        for (SymJob j : jobs) {
            SymResult.Stats st = j.session.stats();
            symStats.methods++;
            symStats.millis += j.used;
            symStats.tuples += j.tuples;
            symStats.testsKept += j.kept;
            symStats.targetsTotal += j.targets.size();
            symStats.targetsReached += j.reached;
            symStats.paths += st.pathsExplored();
            symStats.sat += st.pathsSatisfiable();
            symStats.unsat += st.pathsUnsat();
            symStats.solverTimeouts += st.solverTimeouts();
            if (st.solver() != null) {
                symStats.solverCalls += st.solver().calls();
                symStats.solverMillis += st.solver().millis();
                symStats.bridgeCalls += st.solver().callsWithBridge();
                symStats.bridgeLifted += st.solver().assertionsLifted();
                symStats.bridgeIntVars += st.solver().variablesAsIntegers();
            }
            if (st.receiver() != null) {
                if (j.session.candidates() > 0) { symStats.receiverMethods++; symStats.receiverCandidates += j.session.candidates(); }
                symStats.receiverPaths += st.receiver().paths();
                symStats.receiverFitted += st.receiver().fitted();
                symStats.receiverUnfitted += st.receiver().unfitted();
                symStats.staticPaths += st.receiver().withStatics();
                j.session.unmetFields().forEach((k, v) -> symStats.unmetFields.merge(k, v, Integer::sum));
                symStats.sequences += j.session.sequencesExplored();
                symStats.sequenceTuples += j.session.sequenceTuples();
            }
            symStats.loopHits += st.loopBoundHits();
            symStats.stepHits += st.stepBoundHits();
            symStats.pathBoundHits += st.pathBoundHit();
            if (st.timeBudgetHit()) symStats.timeBudgetHits++;
            if (st.exploded()) symStats.explosions++;
            symStats.inlined += st.inlinedCalls();
            if (!st.finished()) symStats.unfinished++;
            if (!j.targets.isEmpty()) {
                // attribution: which capability each target's branch condition needs, and which of them were reached
                Set<Integer> still = uncoveredLinesOf(c, j.m, out, coverageOf);
                for (var e : symbolic.attribute(j.m, j.targets).entrySet()) {
                    symStats.targetsByCause.merge(e.getValue(), 1, Integer::sum);
                    if (!still.contains(e.getKey())) symStats.reachedByCause.merge(e.getValue(), 1, Integer::sum);
                }
            }
            st.unmodeledCalls().forEach((k, v) -> symStats.unmodeled.merge(k, v, Integer::sum));
            if (log.isDebugEnabled()) log.debug("symbolic {}: allocated {} ms, used {} ms, tuples {}, kept {}, reached {}/{}, stop {}, stats {}",
                    j.m.id(), j.allocated, j.used, j.tuples, j.kept, j.reached, j.targets.size(), st.stopReason(), st);
            j.session.close();
        }
        }
        return jobs;
    }

    private void runSymbolicJob(ClassTarget c, SymJob j, long budget, GenerationConfig config, List<Strategy> strategies, Invoker invoker, OracleBuilder oracle,
                                List<TestCase> out, Map<String, Integer> rejected, Map<TestCase, Map<String, BitSet>> coverageOf) {
        j.allocated += budget;
        long t0 = System.currentTimeMillis();
        List<InputTuple> tuples = j.session.resume(budget);
        j.used += System.currentTimeMillis() - t0;
        j.tuples += tuples.size();
        if (tuples.isEmpty()) return;
        int before = out.size();
        Map<String, Integer> symRejected = new TreeMap<>();
        processTuples(c, j.m, tuples, config, strategies, invoker, oracle, out, symRejected, coverageOf, j.facts, j.names);
        synchronized (symStats) { symRejected.forEach((k, v) -> { rejected.merge(k, v, Integer::sum); symStats.rejected.merge(k, v, Integer::sum); }); }
        j.kept += out.size() - before;
        if (!j.targets.isEmpty()) {
            Set<Integer> after = uncoveredLinesOf(c, j.m, out, coverageOf);
            int reached = 0;
            for (int l : j.targets) if (!after.contains(l)) reached++;
            j.reached = reached;
        }
    }

    private void closePendingSym() {
        for (SymJob j : state.get().pendingSym) j.session.close();
        state.get().pendingSym.clear();
    }

    private static final Set<String> LINKAGE_ERRORS = Set.of("java.lang.LinkageError", "java.lang.NoClassDefFoundError", "java.lang.ExceptionInInitializerError",
            "java.lang.UnsatisfiedLinkError", "java.lang.ClassFormatError", "java.lang.UnsupportedClassVersionError", "java.lang.IncompatibleClassChangeError",
            "java.lang.NoSuchMethodError", "java.lang.NoSuchFieldError", "java.lang.AbstractMethodError", "java.lang.IllegalAccessError", "java.lang.VerifyError",
            "java.lang.BootstrapMethodError", "java.lang.ClassCircularityError");

    /**
     * Drops object candidates that cannot be built (a constructor or factory that throws on its generated
     * arguments). Every tuple containing one would end as a setup failure and use up the method's tuple budget:
     * with Shape.fromNP(int, double) nine receivers in ten are rejected by the factory. Deliberately illegal
     * candidates and non-object values pass; a pool that would end up empty is left as it was.
     */
    private static List<ValuePool> constructible(List<ValuePool> pools, Invoker invoker, Map<String, Integer> rejected) {
        Map<Value, Boolean> known = new HashMap<>();
        List<ValuePool> result = new ArrayList<>(pools.size());
        int dropped = 0;
        for (ValuePool p : pools) {
            ValuePool kept = new ValuePool(p.slot());
            for (Candidate cand : p.candidates()) {
                boolean check = cand.legal() && cand.value() instanceof Value.ObjectValue;
                if (!check || known.computeIfAbsent(cand.value(), invoker::materializes)) kept.add(cand);
            }
            if (kept.isEmpty() || kept.size() == p.size()) { result.add(p); continue; }
            dropped += p.size() - kept.size();
            result.add(kept);
        }
        if (dropped > 0) synchronized (rejected) { rejected.merge("candidate-not-constructible", dropped, Integer::sum); }
        return result;
    }

    /** Executes tuples, filters unstable/state-dependent ones, builds oracles and appends TestCases; returns the number kept. */
    private int processTuples(ClassTarget c, MethodTarget m, List<InputTuple> tuples, GenerationConfig config, List<Strategy> strategies,
                              Invoker invoker, OracleBuilder oracle, List<TestCase> out, Map<String, Integer> rejected,
                              Map<TestCase, Map<String, BitSet>> coverageOf, BodyFacts bodyFacts, Set<String> names) {
        int tests = 0;
        for (InputTuple t : tuples) {
            Outcome o = invoker.execute(t);
            String kind = outcomeKind(o);
            if (!(o instanceof Outcome.Returned) && !(o instanceof Outcome.Threw)) {
                if (o instanceof Outcome.SetupFailed sf && log.isDebugEnabled()) log.debug("setup failed for {}: {} {}", m.id(), sf.exceptionClass(), sf.message());
                if (o instanceof Outcome.SetupFailed sf && (sf.exceptionClass().endsWith("IllegalStateException") || sf.exceptionClass().endsWith("NoClassDefFoundError"))
                        && suspiciousSetupFailures.incrementAndGet() <= 60) {
                    log.info("setup failure worth a look for {} on {}: {} {}", m.id(), Thread.currentThread().getName(), sf.exceptionClass(), sf.message());
                }
                rejected.merge(kind, 1, Integer::sum);
                continue;
            }
            // a linkage error says how far class loading got in this JVM (a native library that is missing, a static
            // initializer that failed earlier and now shows as NoClassDefFoundError), not what the method does:
            // a test asserting it holds on this machine, in this order, only
            if (o instanceof Outcome.Threw th && LINKAGE_ERRORS.contains(th.exceptionClass())) {
                rejected.merge("environment:linkage-error", 1, Integer::sum);
                continue;
            }
            // names that announce randomness get a stricter check; the plain check is two repetitions
            boolean random = (c.binaryName() + "#" + m.name()).toLowerCase().contains("random");
            if (!invoker.isStable(t, o, random ? 8 : 2)) {
                rejected.merge("nondeterministic", 1, Integer::sum);
                continue;
            }
            // a class with mutable static state may have recorded an outcome that only holds after
            // earlier tuples ran: re-execute in a fresh class loader and keep the tuple only if it agrees
            if (hasMutableStatics(c) || statefulOuters.contains(outermost(c.binaryName()))) {
                Map<String, BitSet> keepCov = invoker.lastCoverage();
                try (Sandbox fresh = new Sandbox(freshClasspath, null, n -> false, config.blockIo());
                     Invoker freshInvoker = new Invoker(fresh, config.invocationTimeoutMillis())) {
                    Outcome again = freshInvoker.execute(t);
                    if (!o.equals(again)) {
                        rejected.merge("state-dependent", 1, Integer::sum);
                        continue;
                    }
                }
                invoker.restoreCoverage(keepCov);
            }
            Optional<List<Statement>> stmts = oracle.build(t, o);
            if (stmts.isEmpty()) { rejected.merge("no-oracle", 1, Integer::sum); continue; }
            String name = testName(m, o, out.size() + 1, names);
            String label = t.strategies().isEmpty() ? strategyLabel(strategies) : t.strategyLabel();
            Verdict verdict = o instanceof Outcome.Threw threw ? ExceptionClassifier.classify(m, t, threw, bodyFacts) : oracle.contractVerdict(t, o).orElse(null);
            TestCase tc = new TestCase(name, m, t, o, stmts.get(), label, config.seed(), List.of(), verdict);
            coverageOf.put(tc, invoker.lastCoverage());
            out.add(tc);
            tests++;
        }
        return tests;
    }

    /** Lines of m (uncovered lines and lines with missed branches) given the probes hit so far by the class's kept tests. */
    private Set<Integer> uncoveredLinesOf(ClassTarget c, MethodTarget m, List<TestCase> cases, Map<TestCase, Map<String, BitSet>> coverageOf) {
        try {
            byte[] bytes = classBytesCache.computeIfAbsent(c.binaryName(), k -> {
                try { return classBytes(freshClasspath, k); } catch (IOException e) { return null; }
            });
            if (bytes == null) return Set.of();
            BitSet union = null;
            for (TestCase tc : cases) {
                BitSet b = coverageOf.getOrDefault(tc, Map.of()).get(c.binaryName());
                if (b == null) continue;
                if (union == null) union = new BitSet();
                union.or(b);
            }
            CoverageProbe probe = state.get().probe;
            int probeCount = probe == null ? 0 : probe.probeTotals().getOrDefault(c.binaryName(), 0);
            UncoveredTargets.ClassSummary us = UncoveredTargets.analyze(c.binaryName(), bytes, union, probeCount, 2000);
            Set<Integer> lines = new HashSet<>();
            Set<String> keys = new HashSet<>();
            keys.add(m.name() + m.descriptor());
            // with inlining, uncovered lines of the same-class methods m calls are reachable targets too
            if (symbolic != null) keys.addAll(calleeKeys.computeIfAbsent(m, symbolic::sameClassCallees));
            for (UncoveredTargets.Target t : us.targets()) {
                if (keys.contains(t.method() + t.descriptor())) lines.add(t.line());
            }
            if (log.isDebugEnabled()) log.debug("uncovered targets of {}: union {} probes, class targets {}, method lines {}", m.id(),
                    union == null ? -1 : union.cardinality(), us.targets().size(), lines);
            return lines;
        } catch (IOException | RuntimeException e) {
            log.debug("uncovered-target analysis failed for {}", m.id(), e);
            return Set.of();
        }
    }

    private GenerationResult.SymbolicSummary symbolicSummary() {
        if (symbolic == null) return null;
        SymStats s = symStats;
        return new GenerationResult.SymbolicSummary(symConfig.mode().name().toLowerCase(), s.methods, s.paths, s.sat, s.unsat, s.solverTimeouts, s.loopHits, s.stepHits, s.pathBoundHits,
                s.timeBudgetHits, s.tuples, s.testsKept, s.targetsReached, s.targetsTotal, s.millis, new TreeMap<>(s.unmodeled),
                s.explosions, s.inlined, s.unfinished, new TreeMap<>(s.targetsByCause), new TreeMap<>(s.reachedByCause),
                symConfig.regexChannel() ? new GenerationResult.RegexSummary(s.regexMethods, s.regexPaths, s.regexTuples, s.regexPatternTuples, s.regexKept, s.regexReached, s.regexMillis) : null,
                new GenerationResult.SolverSummary(s.solverCalls, s.solverMillis, s.solverTimeouts, s.bridgeCalls, s.bridgeLifted, s.bridgeIntVars, symConfig.bridgeLift()),
                new GenerationResult.ReceiverSummary(s.receiverMethods, s.receiverCandidates, s.receiverPaths, s.receiverFitted, s.receiverUnfitted, s.staticPaths, new TreeMap<>(s.unmetFields),
                        s.sequences, s.sequenceTuples));
    }

    /** Aggregated symbolic-execution counters for the report. */
    static final class SymStats {
        int methods, paths, sat, unsat, solverTimeouts, loopHits, stepHits, pathBoundHits, timeBudgetHits, tuples, testsKept, targetsReached, targetsTotal,
                explosions, inlined, unfinished;
        long millis;
        final Map<String, Integer> rejected = new TreeMap<>(); Map<String, Integer> unmodeled = new TreeMap<>();
        final Map<String, Integer> targetsByCause = new TreeMap<>(), reachedByCause = new TreeMap<>();
        int regexMethods, regexPaths, regexTuples, regexPatternTuples, regexKept, regexReached;
        long regexMillis;
        int solverCalls, bridgeCalls, bridgeLifted, bridgeIntVars;
        int receiverMethods, receiverCandidates, receiverPaths, receiverFitted, receiverUnfitted, staticPaths, sequences, sequenceTuples;
        final Map<String, Integer> unmetFields = new TreeMap<>();
        long solverMillis;
    }

    private static int priority(Strategy s) {
        return switch (s.name()) {
            case AnnotationStrategy.NAME -> 0;
            case AnnotationStrategy.LLM_NAME -> 0;
            case StaticSeedStrategy.NAME -> 1;
            case cn.ac.ios.justin.strategies.SequenceStrategy.NAME -> 2;
            case SpecialValueStrategy.NAME -> 3;
            default -> 4;
        };
    }

    static List<Strategy> strategies(GenerationConfig config) {
        List<Strategy> out = new ArrayList<>();
        for (String s : config.strategies()) {
            switch (s) {
                case "random" -> out.add(new RandomStrategy(Math.max(3, config.tuplesPerMethod() / 2)));
                case "special" -> out.add(new SpecialValueStrategy(SpecialValues.defaults(), Math.max(4, config.tuplesPerMethod())));
                case "annotation" -> out.add(new AnnotationStrategy());
                case "static" -> out.add(new StaticSeedStrategy(Math.max(6, config.tuplesPerMethod())));
                case "sequence" -> out.add(new cn.ac.ios.justin.strategies.SequenceStrategy());
                case "symbolic" -> { } // tuple producer, wired separately (SymbolicStrategy; cascade or direct mode)
                default -> log.warn("unknown strategy '{}' ignored (available: random, special, annotation, static, sequence, symbolic)", s);
            }
        }
        // "symbolic" alone is a valid choice (direct mode); otherwise fall back to random
        if (out.isEmpty() && !config.strategies().contains("symbolic")) out.add(new RandomStrategy(Math.max(3, config.tuplesPerMethod() / 2)));
        return out;
    }

    private static String strategyLabel(List<Strategy> strategies) {
        return String.join("+", strategies.stream().map(Strategy::name).toList());
    }

    static boolean isTestable(ClassTarget c) {
        // interfaces stay in: their static methods are ordinary targets
        return !c.isAnnotation() && !c.isSynthetic() && !c.isAnonymous() && c.isPublic()
                && !c.binaryName().endsWith("package-info") && !c.binaryName().endsWith("module-info");
    }

    static boolean isEligible(MethodTarget m, ClassTarget c, GenerationConfig config, Scope scope) {
        if (m.isStaticInitializer() || m.isSynthetic() || m.isBridge() || m.isAbstract() || m.isNative()) return false;
        // an instance method declared in an abstract class or as an interface default is called on a concrete
        // public subtype (the value generator picks one); without any such subtype no receiver comes and the
        // method yields nothing. Constructors of abstract classes stay out.
        if ((c.isAbstract() || c.isInterface()) && m.isConstructor()) return false;
        if (c.isEnum() && m.isConstructor()) return false;
        if (m.name().equals("main") && m.isStatic()) return false;
        if (m.isPrivate() && !config.includePrivate()) return false;
        if (m.isProtected() && !config.includeProtected()) return false;
        if (!m.isPublic() && !m.isProtected() && !m.isPrivate() && !config.includePrivate()) return false; // package-private
        if (m.isConstructor() && c.isInterface()) return false;
        return scope.coversMethod(m);
    }

    static String testName(MethodTarget m, Outcome o, int n, Set<String> used) {
        String base = m.isConstructor() ? "constructor" : m.name();
        String suffix = o instanceof Outcome.Threw t ? "_throws" + simple(t.exceptionClass()) : "";
        String name = base + suffix + "_" + n;
        while (!used.add(name)) name = name + "x";
        return name;
    }

    private static String simple(String binaryName) {
        int i = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
        return i < 0 ? binaryName : binaryName.substring(i + 1);
    }

    static String outcomeKind(Outcome o) {
        if (o instanceof Outcome.Returned) return "returned";
        if (o instanceof Outcome.Threw t) return "threw:" + simple(t.exceptionClass());
        if (o instanceof Outcome.TimedOut) return "timeout";
        if (o instanceof Outcome.Blocked) return "blocked";
        if (o instanceof Outcome.SetupFailed s) return "setup-failed:" + simple(s.exceptionClass());
        return "unknown";
    }

    private static String describe(Scope s) {
        return s.kind() + (s.value() == null ? "" : ":" + s.value());
    }

    /** JaCoCo agent include pattern covering every target package. */
    private static String packagePattern(List<ClassTarget> targets) {
        Set<String> pkgs = new HashSet<>();
        for (ClassTarget c : targets) pkgs.add(c.packageName());
        if (pkgs.size() == 1) return pkgs.iterator().next() + ".*";
        // several packages: include everything; the analyzer filters afterwards
        return "*";
    }

    private static void writeJson(GenerationResult r, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(file.toFile(), r);
    }
}
