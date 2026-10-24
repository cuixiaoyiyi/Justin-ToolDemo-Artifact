package cn.ac.ios.justin.api;

import cn.ac.ios.justin.runner.CoverageAnalyzer;
import cn.ac.ios.justin.runner.JUnitXmlParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained HTML report (inline CSS/JS/SVG, no network): KPI tiles, coverage meters, strategy
 * and rejection charts, sortable/filterable tables, expandable stack traces, light and dark themes.
 */
public final class HtmlReportWriter {
    private HtmlReportWriter() {}

    // validated reference palette (see dataviz palette): categorical slots and status colours
    private static final String[] SERIES = {"#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"};
    private static final String GOOD = "#0ca30c", WARNING = "#fab219", CRITICAL = "#d03b3b";

    public static void write(GenerationResult r, Path file) throws IOException {
        StringBuilder sb = new StringBuilder(1 << 16);
        head(sb, r);
        sb.append("<body><div class=\"page\">\n");
        header(sb, r);
        kpis(sb, r);
        charts(sb, r);
        suspects(sb, r);
        failures(sb, r);
        classes(sb, r);
        methods(sb, r);
        uncovered(sb, r);
        misc(sb, r);
        sb.append("</div>\n").append(SCRIPT).append("</body></html>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ sections

    private static void head(StringBuilder sb, GenerationResult r) {
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>Justin report: ").append(esc(r.project())).append("</title>\n<style>").append(CSS).append("</style></head>\n");
    }

    private static void header(StringBuilder sb, GenerationResult r) {
        sb.append("<header class=\"top\"><div><h1>Justin report</h1><div class=\"sub\">")
                .append(esc(r.project())).append(" &middot; scope <code>").append(esc(r.scope())).append("</code> &middot; seed ").append(r.seed())
                .append(" &middot; strategies ").append(esc(String.join(", ", r.strategiesEnabled())))
                .append("</div><div class=\"sub muted\">").append(esc(r.runDir())).append(" &middot; generated ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</div></div>");
        sb.append("<button class=\"theme\" onclick=\"toggleTheme()\" title=\"Toggle light/dark\">&#9680;</button></header>\n");
    }

    private static void kpis(StringBuilder sb, GenerationResult r) {
        sb.append("<section class=\"kpis\">\n");
        tile(sb, "Classes in scope", String.valueOf(r.classes().size()), null);
        tile(sb, "Methods tested / considered", r.methodsTested() + " / " + r.methodsConsidered(), null);
        tile(sb, "Tests generated", compact(r.totalTests()), "kept test methods after minimization");
        if (r.execution() != null) {
            var e = r.execution();
            long bad = e.failed() + e.errors();
            tile(sb, "Executed &middot; passed", compact(e.total()) + " &middot; " + compact(e.passed()),
                    bad == 0 ? "<span class=\"ok\">&#10003; all passing</span>" : "<span class=\"bad\">&#10007; " + bad + " failing</span>");
        }
        if (r.coverage() != null) {
            CoverageAnalyzer.Report c = r.coverage();
            meterTile(sb, "Line coverage", c.lines().covered(), c.lines().total(), "of the classes in scope, JaCoCo agent in the forked test run");
            meterTile(sb, "Branch coverage", c.branches().covered(), c.branches().total(), "same measurement");
            meterTile(sb, "Method coverage", c.methods().covered(), c.methods().total(), "same measurement");
        } else if (r.probes() != null) {
            meterTile(sb, "Sandbox probe coverage", r.probes().coveredProbes(), r.probes().totalProbes(), "JaCoCo probes hit while generating (no forked run)");
        }
        if (r.verdicts() != null) {
            var v = r.verdicts();
            tile(sb, "Suspected defects", String.valueOf(v.suspect()),
                    v.suspect() == 0 ? "<span class=\"ok\">none flagged</span>" : "<span class=\"warn\">&#9888; review the list below</span>");
        }
        tile(sb, "Total time", seconds(r.timings().totalMillis()), "generate " + seconds(r.timings().generateMillis()) + " &middot; compile " + seconds(r.timings().compileMillis()) + " &middot; run " + seconds(r.timings().runMillis()));
        if (r.llm() != null) {
            tile(sb, "LLM calls &middot; tokens", r.llm().calls() + " &middot; " + compact(r.llm().inputTokens() + r.llm().outputTokens()),
                    seconds(r.llm().wallMillis()) + " in calls &middot; " + r.llm().methodsAugmented() + " methods augmented");
        }
        sb.append("</section>\n");
    }

    /** One card that says what the numbers describe, so the charts below need no guessing. */
    private static void readingGuide(StringBuilder sb, GenerationResult r) {
        sb.append("<details class=\"card guide\"><summary><h2>How to read this report</h2></summary><ul>");
        sb.append("<li><b>Test</b>: one generated JUnit method, i.e. one input tuple for one target method plus the assertions built from what the sandbox observed. Counts of tests everywhere are these methods after minimization (tests adding no probe coverage are dropped unless --no-minimize).</li>");
        sb.append("<li><b>Strategy / strategy combination</b>: where the <i>input values</i> of a test came from. A test's parameters may come from several strategies (e.g. <code>random+special</code>); <code>symbolic</code> tests come from a solved path condition, <code>llm</code> from model-inferred annotations.</li>");
        sb.append("<li><b>Line / branch / method coverage</b> (KPI tiles and the Classes table): measured by the JaCoCo agent in a separate JVM that runs the kept tests, over the classes in scope. This is the number to quote.</li>");
        sb.append("<li><b>Probe coverage</b> (per-strategy chart, uncovered targets): JaCoCo probes — roughly basic blocks / branch edges — hit <i>inside the generation sandbox</i> while a test's input was executed. It is a per-test attribution tool, not the coverage of the final test suite; it can differ slightly from the forked-run coverage.</li>");
        sb.append("<li><b>Exclusive probes</b>: probes no test without that strategy reaches — what the suite would lose if the strategy were switched off.</li>");
        sb.append("<li><b>Rejected input tuples</b>: inputs that were executed but did not become tests (setup failed, blocked IO, nondeterministic, state-dependent, redundant coverage, ...).</li>");
        sb.append("<li><b>Verdicts</b>: exception tests are classified EXPECTED (contract: declared, explicitly thrown, or provoked by an input violating a known constraint), SUSPECT (likely defect) or UNKNOWN; a returned value violating an annotated contract is also SUSPECT. Suspect tests carry <code>@Tag(\"justin-suspect\")</code>.</li>");
        sb.append("<li><b>Execution outcome</b>: the forked JUnit run of the kept tests; a failing suspect test is the tool reporting a probable bug, not a broken test.</li>");
        sb.append("</ul></details>\n");
    }

    private static void charts(StringBuilder sb, GenerationResult r) {
        readingGuide(sb, r);
        sb.append("<section class=\"grid2\">\n");
        // tests by strategy combination: one series, sequential hue
        sb.append("<div class=\"card\"><h2>Tests by strategy combination</h2><p class=\"muted\">Kept tests, grouped by the strategies that produced their input values (a test can combine values from several).</p>");
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(r.testsByStrategy().entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        hbars(sb, rows.stream().map(e -> new Bar(e.getKey(), e.getValue(), SERIES[0])).toList(), "tests");
        sb.append("</div>\n");

        if (r.probes() != null && !r.probes().byStrategy().isEmpty()) {
            sb.append("<div class=\"card\"><h2>Probe coverage per strategy</h2><p class=\"muted\">JaCoCo probes (&asymp; branches) hit in the generation sandbox by the kept tests that use each strategy; not the line coverage of the forked run above. Exclusive = reached by no test without this strategy.</p><div class=\"legend\"><span><i style=\"background:")
                    .append(SERIES[0]).append("\"></i>probes hit</span><span><i style=\"background:").append(SERIES[1]).append("\"></i>exclusive to the strategy</span></div>");
            List<GroupedBar> gb = new ArrayList<>();
            for (var e : r.probes().byStrategy().entrySet()) gb.add(new GroupedBar(e.getKey(), e.getValue().probes(), e.getValue().exclusiveProbes()));
            groupedBars(sb, gb);
            sb.append("</div>\n");
        }
        if (!r.rejectedTuples().isEmpty()) {
            sb.append("<div class=\"card\"><h2>Rejected input tuples</h2><p class=\"muted\">Executed inputs that did not become tests, by reason.</p>");
            List<Map.Entry<String, Integer>> rej = new ArrayList<>(r.rejectedTuples().entrySet());
            rej.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            hbars(sb, rej.stream().map(e -> new Bar(e.getKey(), e.getValue(), SERIES[0])).toList(), "tuples");
            sb.append("</div>\n");
        }
        if (r.verdicts() != null) {
            var v = r.verdicts();
            sb.append("<div class=\"card\"><h2>Exception test verdicts</h2><p class=\"muted\">Tests whose target threw: is the throw part of the contract (expected), a probable defect (suspect), or undecidable (unknown)?</p>");
            sb.append("<div class=\"legend\"><span><i style=\"background:").append(GOOD).append("\"></i>&#10003; expected (contract)</span><span><i style=\"background:")
                    .append(CRITICAL).append("\"></i>&#9888; suspect</span><span><i style=\"background:").append(WARNING).append("\"></i>? unknown</span></div>");
            stackedBar(sb, new long[]{v.expected(), v.suspect(), v.unknown()}, new String[]{GOOD, CRITICAL, WARNING}, new String[]{"expected", "suspect", "unknown"});
            sb.append("</div>\n");
        }
        if (r.execution() != null) {
            var e = r.execution();
            sb.append("<div class=\"card\"><h2>Execution outcome</h2><p class=\"muted\">Result of running the kept tests in a separate JVM (JUnit Platform).</p>");
            sb.append("<div class=\"legend\"><span><i style=\"background:").append(GOOD).append("\"></i>&#10003; passed</span><span><i style=\"background:")
                    .append(CRITICAL).append("\"></i>&#10007; failed / error</span><span><i style=\"background:").append(WARNING).append("\"></i>&#8722; skipped</span></div>");
            stackedBar(sb, new long[]{e.passed(), e.failed() + e.errors(), e.skipped()}, new String[]{GOOD, CRITICAL, WARNING}, new String[]{"passed", "failed", "skipped"});
            if (!e.orderDependent().isEmpty()) sb.append("<p class=\"muted\">").append(e.orderDependent().size()).append(" order-dependent test(s) removed from the sources (they pass alone, fail after other tests).</p>");
            sb.append("</div>\n");
        }
        if (r.llm() != null) {
            var l = r.llm();
            sb.append("<div class=\"card\"><h2>Model-inferred annotations (LLM)</h2><table class=\"plain\">");
            kv(sb, "Endpoint / model", esc(l.endpoint()));
            kv(sb, "Calls / cache hits / failures", l.calls() + " / " + l.cacheHits() + " / " + l.failures() + (l.failureReasons().isEmpty() ? "" : " " + esc(l.failureReasons().toString())));
            kv(sb, "Methods augmented", String.valueOf(l.methodsAugmented()));
            kv(sb, "Time in model calls", seconds(l.wallMillis()) + " total &middot; " + l.averageMillis() + " ms average &middot; " + l.maxMillis() + " ms max");
            kv(sb, "Tokens in / out", compact(l.inputTokens()) + " / " + compact(l.outputTokens()));
            sb.append("</table></div>\n");
        }
        if (r.symbolic() != null) {
            var y = r.symbolic();
            sb.append("<div class=\"card\"><h2>Symbolic execution</h2><table class=\"plain\">");
            kv(sb, "Mode", "direct".equals(y.mode()) ? "direct (every method, independent of the other strategies)" : "cascade (stage C: only lines the other strategies left uncovered)");
            kv(sb, "direct".equals(y.mode()) ? "Methods explored" : "Methods with uncovered targets", String.valueOf(y.methods()));
            kv(sb, "Paths explored / satisfiable / unsat", y.pathsExplored() + " / " + y.pathsSatisfiable() + " / " + y.pathsUnsat());
            kv(sb, "Tuples produced / tests kept", y.tuples() + " / " + y.testsKept());
            kv(sb, "Uncovered lines reached", y.targetsReached() + " / " + y.targetsTotal());
            if (y.solver() != null) kv(sb, "Solver: calls / seconds / with int-string conversions / assertions restated by the bridge lift",
                    y.solver().calls() + " / " + String.format(java.util.Locale.ROOT, "%.1f", y.solver().millis() / 1000.0) + " / " + (y.solver().bridgeLift() ? y.solver().callsWithBridge() + " / " + y.solver().assertionsLifted() : "not counted / bridge lift off"));
            kv(sb, "Bound hits: loop / step / paths / time / solver timeouts", y.loopBoundHits() + " / " + y.stepBoundHits() + " / " + y.pathBoundHits() + " / " + y.timeBudgetHits() + " / " + y.solverTimeouts());
            kv(sb, "Time", seconds(y.millis()));
            kv(sb, "Methods cut as exploding / left unfinished", y.explosions() + " / " + y.unfinished());
            kv(sb, "Same-class calls inlined", String.valueOf(y.inlinedCalls()));
            if (y.regex() != null) {
                var x = y.regex();
                kv(sb, "Regex channel (string parameters, before the solver)", x.methods() + " methods, " + x.stringPaths() + " string paths, " + x.tuples() + " path tuples + "
                        + x.patternTuples() + " pattern tuples, " + x.testsKept() + " tests kept, " + x.targetsReached() + " uncovered lines reached, " + seconds(x.millis()));
            }
            if (!y.unmodeledCalls().isEmpty()) {
                List<Map.Entry<String, Integer>> top = new ArrayList<>(y.unmodeledCalls().entrySet());
                top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                StringBuilder u = new StringBuilder();
                for (int i = 0; i < Math.min(12, top.size()); i++) u.append(i > 0 ? ", " : "").append(esc(top.get(i).getKey())).append(" (").append(top.get(i).getValue()).append(')');
                kv(sb, "Most frequent unmodeled callees", u.toString());
            }
            sb.append("</table>");
            if (!y.targetsByCauseOrEmpty().isEmpty()) {
                sb.append("<p class=\"muted\">Targets by what their branch condition depends on (def-use closure in the method body): where the executor reaches its targets and where a capability is missing.</p>")
                  .append("<table class=\"plain\"><thead><tr><th>Depends on</th><th class=\"num\">Targets</th><th class=\"num\">Reached</th><th class=\"num\">Rate</th></tr></thead><tbody>");
                List<Map.Entry<String, Integer>> causes = new ArrayList<>(y.targetsByCauseOrEmpty().entrySet());
                causes.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                for (var e : causes) {
                    int reached = y.reachedByCauseOrEmpty().getOrDefault(e.getKey(), 0);
                    sb.append("<tr><td>").append(esc(e.getKey())).append("</td>").append(num(e.getValue())).append(num(reached))
                      .append("<td class=\"num\">").append(pctOnly(reached, e.getValue())).append("</td></tr>");
                }
                sb.append("</tbody></table>");
            }
            symbolicMethods(sb, r);
            sb.append("</div>\n");
        }
        if (r.nway() != null || r.probes() != null) {
            sb.append("<div class=\"card\"><h2>Generation metrics</h2><table class=\"plain\">");
            if (r.probes() != null) kv(sb, "Sandbox probe coverage", pct(r.probes().coveredProbes(), r.probes().totalProbes()));
            if (r.nway() != null) kv(sb, r.nway().strength() + "-way interaction coverage", pct(r.nway().coveredInteractions(), r.nway().totalInteractions()));
            if (r.compile() != null) kv(sb, "Files compiled / failed", r.compile().filesCompiled() + " / " + r.compile().filesFailed());
            if (r.compile() != null) kv(sb, "Tests pruned by javac", String.valueOf(r.compile().prunedTests().size()));
            var t = r.timings();
            kv(sb, "Time (s): scan / generate / emit / compile / run / coverage", t.scanMillis() / 1000 + " / " + t.generateMillis() / 1000 + " / " + t.emitMillis() / 1000 + " / " + t.compileMillis() / 1000 + " / " + t.runMillis() / 1000 + " / " + t.coverageMillis() / 1000);
            sb.append("</table></div>\n");
        }
        sb.append("</section>\n");
    }

    private static void suspects(StringBuilder sb, GenerationResult r) {
        if (r.verdicts() == null || r.verdicts().suspects().isEmpty()) return;
        sb.append("<section class=\"card\"><h2>&#9888; Suspected defects (").append(r.verdicts().suspects().size()).append(")</h2>");
        sb.append("<p class=\"muted\">Implicit runtime exceptions on legal input with no explicit throw of that type in the method body. Each is emitted as a regression test tagged <code>justin-suspect</code>; review before trusting.</p>");
        sb.append("<table class=\"sortable\" id=\"suspects\"><thead><tr><th>Test</th><th>Target</th><th>Exception</th><th>Evidence</th><th>Strategy</th><th>Source</th></tr></thead><tbody>");
        for (GenerationResult.Suspect s : r.verdicts().suspects()) {
            sb.append("<tr><td><code>").append(esc(simple(s.testClass()))).append('#').append(esc(s.test())).append("</code></td><td><code>").append(esc(s.targetMethod()))
                    .append("</code></td><td><span class=\"pill crit\">").append(esc(s.exception())).append("</span></td><td>").append(esc(s.evidence()))
                    .append("</td><td>").append(esc(s.strategy())).append("</td><td>").append(esc(s.source())).append("</td></tr>");
        }
        sb.append("</tbody></table></section>\n");
    }

    private static void failures(StringBuilder sb, GenerationResult r) {
        if (r.execution() == null) return;
        var e = r.execution();
        if (!e.orderDependent().isEmpty()) {
            sb.append("<section class=\"card\"><h2>Order-dependent tests removed (").append(e.orderDependent().size()).append(")</h2>");
            sb.append("<p class=\"muted\">Failed in the full run but pass alone: static state left behind by other tests changes their result. Removed from the generated sources.</p><ul>");
            for (String k : e.orderDependent()) sb.append("<li><code>").append(esc(k)).append("</code></li>");
            sb.append("</ul></section>\n");
        }
        if (e.failures().isEmpty()) return;
        sb.append("<section class=\"card\"><h2>&#10007; Failed tests (").append(e.failures().size()).append(")</h2>");
        for (JUnitXmlParser.TestResult f : e.failures()) {
            GenerationResult.TestInfo info = find(r, f.className(), f.methodName());
            sb.append("<details class=\"fail\"><summary><code>").append(esc(simple(f.className()))).append('#').append(esc(f.methodName())).append("</code> <span class=\"pill crit\">")
                    .append(esc(f.status().name())).append("</span> ").append(esc(f.exceptionType() == null ? "" : simple(f.exceptionType()))).append("</summary><div class=\"body\">");
            if (info != null) {
                sb.append("<div><b>Target</b> <code>").append(esc(info.targetMethod())).append("</code></div>");
                sb.append("<div><b>Strategy</b> ").append(esc(info.strategy())).append(" &middot; <b>source</b> ").append(esc(info.source())).append(" &middot; <b>sandbox outcome</b> ").append(esc(info.outcomeKind())).append("</div>");
            }
            if (f.message() != null && !f.message().isBlank()) sb.append("<div><b>Message</b> ").append(esc(f.message())).append("</div>");
            if (f.stackTrace() != null && !f.stackTrace().isBlank()) sb.append("<pre>").append(esc(f.stackTrace())).append("</pre>");
            sb.append("</div></details>");
        }
        sb.append("</section>\n");
    }

    private static void classes(StringBuilder sb, GenerationResult r) {
        Map<String, CoverageAnalyzer.ClassCoverage> cov = new HashMap<>();
        if (r.coverage() != null) for (CoverageAnalyzer.ClassCoverage c : r.coverage().classes()) cov.put(c.binaryName(), c);
        sb.append("<section class=\"card\"><h2>Classes (").append(r.classes().size()).append(")</h2>");
        sb.append("<input class=\"filter\" placeholder=\"Filter classes...\" oninput=\"filterTable('classes', this.value)\">");
        sb.append("<table class=\"sortable\" id=\"classes\"><thead><tr><th>Class</th><th class=\"num\">Methods considered</th><th class=\"num\">Methods tested</th><th class=\"num\">Tests</th><th class=\"num\">Passed</th><th class=\"num\">Failed</th><th class=\"num\">Suspect</th><th>Line coverage</th><th>Branch coverage</th></tr></thead><tbody>");
        for (GenerationResult.ClassResult c : r.classes()) {
            long passed = c.tests().stream().filter(x -> "PASSED".equals(x.executionStatus())).count();
            long failed = c.tests().stream().filter(x -> x.executionStatus() != null && !"PASSED".equals(x.executionStatus()) && !"SKIPPED".equals(x.executionStatus())).count();
            long suspect = c.tests().stream().filter(x -> "SUSPECT".equals(x.verdict())).count();
            CoverageAnalyzer.ClassCoverage cc = cov.get(c.binaryName());
            sb.append("<tr><td><code>").append(esc(c.binaryName())).append("</code></td>")
                    .append(num(c.methodsConsidered())).append(num(c.methodsTested())).append(num(c.tests().size())).append(num(passed))
                    .append("<td class=\"num").append(failed > 0 ? " bad" : "").append("\">").append(failed).append("</td>")
                    .append("<td class=\"num").append(suspect > 0 ? " warn" : "").append("\">").append(suspect).append("</td>");
            if (cc != null) { sb.append(meterCell(cc.lines().covered(), cc.lines().total())).append(meterCell(cc.branches().covered(), cc.branches().total())); }
            else if (c.sandboxBranchesTotal() > 0) { sb.append("<td class=\"muted\">n/a</td>").append(meterCell(c.sandboxBranchesCovered(), c.sandboxBranchesTotal())); }
            else sb.append("<td class=\"muted\">n/a</td><td class=\"muted\">n/a</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></section>\n");
    }

    private static void methods(StringBuilder sb, GenerationResult r) {
        sb.append("<section class=\"card\"><h2>Methods</h2><input class=\"filter\" placeholder=\"Filter methods...\" oninput=\"filterTable('methods', this.value)\">");
        sb.append("<table class=\"sortable\" id=\"methods\"><thead><tr><th>Method</th><th class=\"num\">Tests</th><th class=\"num\">Return</th><th class=\"num\">Exception</th><th class=\"num\">Expected</th><th class=\"num\">Suspect</th><th class=\"num\">Unknown</th><th class=\"num\">Passed</th><th class=\"num\">Failed</th></tr></thead><tbody>");
        for (GenerationResult.ClassResult c : r.classes()) {
            for (GenerationResult.MethodSummary m : c.methods()) {
                sb.append("<tr><td><code>").append(esc(m.method())).append("</code></td>").append(num(m.tests())).append(num(m.returnTests())).append(num(m.exceptionTests()))
                        .append(num(m.expected())).append("<td class=\"num").append(m.suspect() > 0 ? " warn" : "").append("\">").append(m.suspect()).append("</td>")
                        .append(num(m.unknown())).append(num(m.passed())).append("<td class=\"num").append(m.failed() > 0 ? " bad" : "").append("\">").append(m.failed()).append("</td></tr>");
            }
        }
        sb.append("</tbody></table></section>\n");
    }

    private static void uncovered(StringBuilder sb, GenerationResult r) {
        if (r.classes().stream().noneMatch(c -> !c.uncovered().isEmpty())) return;
        sb.append("<section class=\"card\"><h2>Uncovered targets (sandbox measurement)</h2><p class=\"muted\">Lines and branches the kept tests do not reach; the inputs for directed strategies. Up to 40 per class.</p>");
        for (GenerationResult.ClassResult c : r.classes()) {
            if (c.uncovered().isEmpty()) continue;
            sb.append("<details><summary><code>").append(esc(c.binaryName())).append("</code> &middot; ").append(c.uncovered().size()).append(" target(s) &middot; branches ")
                    .append(c.sandboxBranchesCovered()).append('/').append(c.sandboxBranchesTotal()).append("</summary><table class=\"plain\"><thead><tr><th>Method</th><th class=\"num\">Line</th><th class=\"num\">Missed branches</th><th>Line reached</th></tr></thead><tbody>");
            int n = 0;
            for (GenerationResult.Uncovered u : c.uncovered()) {
                if (n++ >= 40) { sb.append("<tr><td colspan=\"4\" class=\"muted\">...</td></tr>"); break; }
                sb.append("<tr><td><code>").append(esc(u.method())).append("</code></td>").append(num(u.line())).append("<td class=\"num\">").append(u.missedBranches()).append('/').append(u.totalBranches())
                        .append("</td><td>").append(u.lineUncovered() ? "<span class=\"pill crit\">no</span>" : "<span class=\"pill ok\">yes</span>").append("</td></tr>");
            }
            sb.append("</tbody></table></details>");
        }
        sb.append("</section>\n");
    }

    private static void misc(StringBuilder sb, GenerationResult r) {
        if (r.compile() != null && !r.compile().prunedTests().isEmpty()) {
            sb.append("<section class=\"card\"><details><summary><h2 class=\"inline\">Tests pruned at compile time (").append(r.compile().prunedTests().size()).append(")</h2></summary><ul>");
            for (String p : r.compile().prunedTests()) sb.append("<li><code>").append(esc(p)).append("</code></li>");
            sb.append("</ul></details></section>\n");
        }
        if (r.compile() != null && !r.compile().failures().isEmpty()) {
            sb.append("<section class=\"card\"><details open><summary><h2 class=\"inline\">Files that failed to compile (").append(r.compile().failures().size()).append(")</h2></summary>");
            for (var e : r.compile().failures().entrySet()) {
                sb.append("<div><code>").append(esc(e.getKey())).append("</code><pre>").append(esc(String.join("\n", e.getValue()))).append("</pre></div>");
            }
            sb.append("</details></section>\n");
        }
        if (!r.warnings().isEmpty()) {
            sb.append("<section class=\"card\"><h2>Warnings</h2><ul>");
            for (String w : r.warnings()) sb.append("<li>").append(esc(w)).append("</li>");
            sb.append("</ul></section>\n");
        }
        sb.append("<footer class=\"muted\">Justin &middot; report.json next to this file holds the same data in machine-readable form.</footer>\n");
    }

    // ------------------------------------------------------------------ chart pieces (inline SVG)

    private record Bar(String label, long value, String color) {}
    private record GroupedBar(String label, long a, long b) {}

    /** Horizontal bars: 20px thick, rounded data end, value at the tip, hover title. */
    private static void hbars(StringBuilder sb, List<Bar> bars, String unit) {
        if (bars.isEmpty()) { sb.append("<p class=\"muted\">nothing to show</p>"); return; }
        long max = bars.stream().mapToLong(Bar::value).max().orElse(1);
        int labelW = 190, plotW = 360, rowH = 28, h = bars.size() * rowH + 8;
        sb.append("<svg class=\"chart\" viewBox=\"0 0 ").append(labelW + plotW + 70).append(' ').append(h).append("\" role=\"img\" aria-label=\"bar chart\">");
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            int y = i * rowH + 4;
            double w = max == 0 ? 0 : plotW * (double) b.value() / max;
            sb.append("<text x=\"").append(labelW - 8).append("\" y=\"").append(y + 15).append("\" text-anchor=\"end\" class=\"lbl\">").append(esc(truncate(b.label(), 28))).append("</text>");
            sb.append("<rect x=\"").append(labelW).append("\" y=\"").append(y).append("\" width=\"").append(fmt(Math.max(w, 2))).append("\" height=\"20\" rx=\"4\" fill=\"").append(b.color()).append("\"><title>")
                    .append(esc(b.label())).append(": ").append(b.value()).append(' ').append(unit).append("</title></rect>");
            // square the baseline side: overlay a 4px unrounded strip
            sb.append("<rect x=\"").append(labelW).append("\" y=\"").append(y).append("\" width=\"").append(fmt(Math.min(4, Math.max(w, 2)))).append("\" height=\"20\" fill=\"").append(b.color()).append("\"/>");
            sb.append("<text x=\"").append(fmt(labelW + Math.max(w, 2) + 6)).append("\" y=\"").append(y + 15).append("\" class=\"val\">").append(b.value()).append("</text>");
        }
        sb.append("</svg>");
    }

    private static void groupedBars(StringBuilder sb, List<GroupedBar> bars) {
        long max = bars.stream().mapToLong(b -> Math.max(b.a(), b.b())).max().orElse(1);
        int labelW = 120, plotW = 400, rowH = 40, h = bars.size() * rowH + 8;
        sb.append("<svg class=\"chart\" viewBox=\"0 0 ").append(labelW + plotW + 80).append(' ').append(h).append("\" role=\"img\" aria-label=\"grouped bar chart\">");
        for (int i = 0; i < bars.size(); i++) {
            GroupedBar b = bars.get(i);
            int y = i * rowH + 4;
            sb.append("<text x=\"").append(labelW - 8).append("\" y=\"").append(y + 22).append("\" text-anchor=\"end\" class=\"lbl\">").append(esc(b.label())).append("</text>");
            double wa = plotW * (double) b.a() / max, wb = plotW * (double) b.b() / max;
            sb.append("<rect x=\"").append(labelW).append("\" y=\"").append(y).append("\" width=\"").append(fmt(Math.max(wa, 2))).append("\" height=\"14\" rx=\"4\" fill=\"").append(SERIES[0]).append("\"><title>").append(esc(b.label())).append(": ").append(b.a()).append(" probes hit</title></rect>");
            sb.append("<text x=\"").append(fmt(labelW + Math.max(wa, 2) + 6)).append("\" y=\"").append(y + 11).append("\" class=\"val\">").append(b.a()).append("</text>");
            sb.append("<rect x=\"").append(labelW).append("\" y=\"").append(y + 18).append("\" width=\"").append(fmt(Math.max(wb, 2))).append("\" height=\"14\" rx=\"4\" fill=\"").append(SERIES[1]).append("\"><title>").append(esc(b.label())).append(": ").append(b.b()).append(" exclusive probes</title></rect>");
            sb.append("<text x=\"").append(fmt(labelW + Math.max(wb, 2) + 6)).append("\" y=\"").append(y + 29).append("\" class=\"val\">").append(b.b()).append("</text>");
        }
        sb.append("</svg>");
    }

    /** One stacked 100% bar with 2px surface gaps between segments and a value table below it. */
    private static void stackedBar(StringBuilder sb, long[] values, String[] colors, String[] names) {
        long total = 0;
        for (long v : values) total += v;
        sb.append("<svg class=\"chart\" viewBox=\"0 0 600 28\" role=\"img\" aria-label=\"stacked bar\">");
        double x = 0;
        if (total == 0) sb.append("<rect x=\"0\" y=\"4\" width=\"600\" height=\"20\" rx=\"4\" class=\"track\"/>");
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) continue;
            double w = 600.0 * values[i] / total;
            sb.append("<rect x=\"").append(fmt(x + (x > 0 ? 2 : 0))).append("\" y=\"4\" width=\"").append(fmt(Math.max(w - (x > 0 ? 2 : 0), 1))).append("\" height=\"20\" rx=\"4\" fill=\"").append(colors[i]).append("\"><title>")
                    .append(names[i]).append(": ").append(values[i]).append(" (").append(pctOnly(values[i], total)).append(")</title></rect>");
            x += w;
        }
        sb.append("</svg><table class=\"plain\"><tr>");
        for (int i = 0; i < values.length; i++) sb.append("<td><i class=\"sw\" style=\"background:").append(colors[i]).append("\"></i>").append(names[i]).append(" <b>").append(values[i]).append("</b> <span class=\"muted\">").append(pctOnly(values[i], total)).append("</span></td>");
        sb.append("</tr></table>");
    }

    private static void tile(StringBuilder sb, String label, String value, String note) {
        sb.append("<div class=\"tile\"><div class=\"label\">").append(label).append("</div><div class=\"value\">").append(value).append("</div>");
        if (note != null) sb.append("<div class=\"note\">").append(note).append("</div>");
        sb.append("</div>");
    }

    private static void meterTile(StringBuilder sb, String label, long covered, long total) { meterTile(sb, label, covered, total, null); }

    private static void meterTile(StringBuilder sb, String label, long covered, long total, String caption) {
        double ratio = total == 0 ? 0 : (double) covered / total;
        sb.append("<div class=\"tile\"><div class=\"label\">").append(label).append("</div><div class=\"value\">").append(String.format("%.1f%%", 100 * ratio))
                .append("</div><div class=\"meter\"><div style=\"width:").append(String.format("%.1f", 100 * ratio)).append("%\"></div></div><div class=\"note\">")
                .append(compact(covered)).append(" of ").append(compact(total)).append(caption == null ? "" : " &middot; " + caption).append("</div></div>");
    }

    private static String meterCell(long covered, long total) {
        double ratio = total == 0 ? 0 : (double) covered / total;
        return "<td data-sort=\"" + fmt(ratio) + "\"><div class=\"meterrow\"><div class=\"meter\"><div style=\"width:" + String.format("%.1f", 100 * ratio) + "%\"></div></div><span>"
                + String.format("%.1f%%", 100 * ratio) + "</span></div></td>";
    }

    // ------------------------------------------------------------------ helpers

    private static GenerationResult.TestInfo find(GenerationResult r, String testClass, String name) {
        for (GenerationResult.ClassResult c : r.classes()) {
            if (!testClass.equals(c.testClass())) continue;
            for (GenerationResult.TestInfo t : c.tests()) if (t.name().equals(name)) return t;
        }
        return null;
    }

    /** Plan 7.3 report: the methods that cost the most symbolic-execution time, with what they yielded. */
    private static void symbolicMethods(StringBuilder sb, GenerationResult r) {
        List<GenerationResult.SymMethod> all = new ArrayList<>();
        for (GenerationResult.ClassResult c : r.classes()) all.addAll(c.symbolicOrEmpty());
        if (all.isEmpty()) return;
        all.sort((a, b) -> Long.compare(b.usedMs(), a.usedMs()));
        int shown = Math.min(40, all.size());
        sb.append("<details><summary>Per-method budget and yield (").append(shown).append(" of ").append(all.size())
          .append(" methods, most time first)</summary><table class=\"plain\"><thead><tr><th>Method</th><th class=\"num\">Allocated ms</th><th class=\"num\">Used ms</th>")
          .append("<th class=\"num\">Sat paths</th><th class=\"num\">Tests kept</th><th class=\"num\">Targets reached</th><th class=\"num\">Inlined</th><th>Stopped by</th></tr></thead><tbody>");
        for (int i = 0; i < shown; i++) {
            GenerationResult.SymMethod m = all.get(i);
            String stop = m.stopReason() != null ? m.stopReason() : (m.finished() ? "finished" : "budget");
            sb.append("<tr><td>").append(esc(m.method())).append("</td>").append(num(m.allocatedMs())).append(num(m.usedMs()))
              .append(num(m.pathsSatisfiable())).append(num(m.testsKept())).append("<td class=\"num\">").append(m.targetsReached()).append('/').append(m.targets()).append("</td>")
              .append(num(m.inlinedCalls())).append("<td>").append(esc(stop)).append("</td></tr>");
        }
        sb.append("</tbody></table></details>");
    }

    private static void kv(StringBuilder sb, String k, String v) { sb.append("<tr><th>").append(k).append("</th><td>").append(v).append("</td></tr>"); }
    private static String num(long v) { return "<td class=\"num\">" + v + "</td>"; }
    private static String pct(long a, long b) { return b == 0 ? "n/a" : String.format("%d / %d (%.1f%%)", a, b, 100.0 * a / b); }
    private static String pctOnly(long a, long b) { return b == 0 ? "0%" : String.format("%.1f%%", 100.0 * a / b); }
    private static String seconds(long ms) { return ms >= 60_000 ? String.format("%.1f min", ms / 60000.0) : String.format("%.1f s", ms / 1000.0); }
    private static String fmt(double d) { return String.format(java.util.Locale.ROOT, "%.1f", d); }
    private static String compact(long v) { return v >= 1_000_000 ? String.format("%.1fM", v / 1e6) : v >= 10_000 ? String.format("%.1fK", v / 1e3) : String.valueOf(v); }
    private static String simple(String binary) { int i = binary.lastIndexOf('.'); return i < 0 ? binary : binary.substring(i + 1); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;"); case '<' -> b.append("&lt;"); case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;"); case '\'' -> b.append("&#39;"); default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ static assets

    private static final String CSS = """
            :root{color-scheme:light;--page:#f9f9f7;--surface:#fcfcfb;--ink:#0b0b0b;--ink2:#52514e;--muted:#898781;--grid:#e1e0d9;--border:rgba(11,11,11,.10);
              --accent:#2a78d6;--track:#cde2fb;--good:#006300;--bad:#d03b3b;--warn:#b26e00;--pill-ok:#e3f5e3;--pill-crit:#fbe3e3}
            @media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;--page:#0d0d0d;--surface:#1a1a19;--ink:#fff;--ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;--border:rgba(255,255,255,.10);
              --accent:#3987e5;--track:#184f95;--good:#0ca30c;--bad:#e66767;--warn:#fab219;--pill-ok:#123a12;--pill-crit:#4a1c1c}}
            :root[data-theme=dark]{color-scheme:dark;--page:#0d0d0d;--surface:#1a1a19;--ink:#fff;--ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;--border:rgba(255,255,255,.10);
              --accent:#3987e5;--track:#184f95;--good:#0ca30c;--bad:#e66767;--warn:#fab219;--pill-ok:#123a12;--pill-crit:#4a1c1c}
            *{box-sizing:border-box}body{margin:0;background:var(--page);color:var(--ink);font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
            .page{max-width:1280px;margin:0 auto;padding:20px 24px 48px}
            header.top{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:16px}
            h1{font-size:22px;margin:0 0 4px}h2{font-size:15px;margin:0 0 10px;font-weight:600}h2.inline{display:inline;margin:0}
            .sub{color:var(--ink2)}.muted{color:var(--muted)}code{font:12.5px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;word-break:break-all}
            button.theme{border:1px solid var(--border);background:var(--surface);color:var(--ink);border-radius:8px;padding:6px 10px;cursor:pointer;font-size:16px}
            .kpis{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px;margin-bottom:16px}
            .tile,.card{background:var(--surface);border:1px solid var(--border);border-radius:10px;padding:14px 16px}
            .tile .label{color:var(--ink2);font-size:12.5px}.tile .value{font-size:26px;font-weight:600;margin:2px 0 4px}.tile .note{font-size:12px;color:var(--muted)}
            .ok{color:var(--good)}.bad{color:var(--bad)}.warn{color:var(--warn)}
            .meter{height:8px;background:var(--track);border-radius:4px;overflow:hidden}.meter div{height:100%;background:var(--accent);border-radius:4px}
            .meterrow{display:flex;align-items:center;gap:8px}.meterrow .meter{flex:1;min-width:80px}.meterrow span{font-variant-numeric:tabular-nums;min-width:52px;text-align:right}
            .grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(440px,1fr));gap:12px;margin-bottom:12px}
            section.card{margin-bottom:12px}
            details.guide{margin-bottom:12px}details.guide summary{cursor:pointer;list-style:none}details.guide summary h2{display:inline;margin:0}details.guide summary::before{content:"\25B8 ";color:var(--muted)}details.guide[open] summary::before{content:"\25BE "}details.guide ul{margin:8px 0 0 18px;color:var(--ink2);font-size:13px;line-height:1.5}
            svg.chart{width:100%;height:auto;display:block;overflow:visible}svg .lbl{font-size:12px;fill:var(--ink2)}svg .val{font-size:12px;fill:var(--ink);font-variant-numeric:tabular-nums}
            svg .track{fill:var(--track)}
            .legend{display:flex;gap:16px;flex-wrap:wrap;font-size:12.5px;color:var(--ink2);margin-bottom:8px}.legend i,i.sw{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px;vertical-align:-1px}
            table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--grid);vertical-align:top}
            th{color:var(--ink2);font-weight:600;font-size:12.5px}td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}
            table.sortable th{cursor:pointer;user-select:none;white-space:nowrap}table.sortable th:hover{color:var(--accent)}table.sortable th.asc::after{content:" \\25B4"}table.sortable th.desc::after{content:" \\25BE"}
            table.plain th{width:auto}table.plain td,table.plain th{border-bottom:none;padding:4px 8px}
            input.filter{width:100%;max-width:420px;padding:7px 10px;margin-bottom:10px;border:1px solid var(--border);border-radius:8px;background:var(--page);color:var(--ink)}
            .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px}.pill.ok{background:var(--pill-ok);color:var(--good)}.pill.crit{background:var(--pill-crit);color:var(--bad)}
            details{margin:6px 0}summary{cursor:pointer;padding:4px 0}details .body{padding:6px 0 4px 16px}pre{background:var(--page);border:1px solid var(--border);border-radius:8px;padding:10px;overflow-x:auto;font-size:12px;line-height:1.4}
            footer{margin-top:24px;font-size:12px}
            """;

    private static final String SCRIPT = """
            <script>
            function toggleTheme(){var r=document.documentElement;var dark=r.getAttribute('data-theme')==='dark'||(!r.getAttribute('data-theme')&&matchMedia('(prefers-color-scheme: dark)').matches);r.setAttribute('data-theme',dark?'light':'dark');}
            function filterTable(id,q){q=q.toLowerCase();document.querySelectorAll('#'+id+' tbody tr').forEach(function(tr){tr.style.display=tr.textContent.toLowerCase().indexOf(q)>=0?'':'none';});}
            document.querySelectorAll('table.sortable th').forEach(function(th,idx){th.addEventListener('click',function(){var t=th.closest('table'),tb=t.tBodies[0],rows=Array.from(tb.rows);var asc=!th.classList.contains('asc');
              t.querySelectorAll('th').forEach(function(h){h.classList.remove('asc','desc');});th.classList.add(asc?'asc':'desc');
              rows.sort(function(a,b){var x=key(a.cells[idx]),y=key(b.cells[idx]);if(typeof x==='number'&&typeof y==='number')return asc?x-y:y-x;return asc?String(x).localeCompare(String(y)):String(y).localeCompare(String(x));});
              rows.forEach(function(r){tb.appendChild(r);});});});
            function key(td){if(!td)return '';if(td.dataset.sort!==undefined)return parseFloat(td.dataset.sort);var s=td.textContent.trim();var n=parseFloat(s.replace(/[,%]/g,''));return isNaN(n)?s:n;}
            </script>
            """;
}
