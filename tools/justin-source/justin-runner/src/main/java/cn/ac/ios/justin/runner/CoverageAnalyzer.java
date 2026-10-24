package cn.ac.ios.justin.runner;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.html.HTMLFormatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Turns a jacoco.exec plus the subject class files into per-class / per-method coverage numbers and an HTML report. */
public final class CoverageAnalyzer {

    public record Counter(int covered, int total) {
        public double ratio() { return total == 0 ? 1.0 : (double) covered / total; }
        static Counter of(ICounter c) { return new Counter(c.getCoveredCount(), c.getTotalCount()); }
    }

    public record MethodCoverage(String name, String descriptor, int firstLine, Counter instructions, Counter branches, Counter lines) {}

    public record ClassCoverage(String binaryName, Counter instructions, Counter branches, Counter lines, Counter methods, List<MethodCoverage> methodsDetail) {}

    public record Report(List<ClassCoverage> classes, Counter instructions, Counter branches, Counter lines, Counter methods) {}

    private CoverageAnalyzer() {}

    public static Report analyze(Path execFile, List<Path> classRoots, Predicate<String> classFilter, Path htmlOut, List<Path> sourceRoots) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        if (Files.exists(execFile)) loader.load(execFile.toFile());
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        for (Path root : classRoots) analyzer.analyzeAll(root.toFile());
        IBundleCoverage bundle = builder.getBundle("justin");

        List<ClassCoverage> classes = new ArrayList<>();
        int ic = 0, it = 0, bc = 0, bt = 0, lc = 0, lt = 0, mc = 0, mt = 0;
        for (IClassCoverage cc : builder.getClasses()) {
            String name = cc.getName().replace('/', '.');
            if (!classFilter.test(name)) continue;
            List<MethodCoverage> ms = new ArrayList<>();
            for (IMethodCoverage m : cc.getMethods()) {
                ms.add(new MethodCoverage(m.getName(), m.getDesc(), m.getFirstLine(),
                        Counter.of(m.getInstructionCounter()), Counter.of(m.getBranchCounter()), Counter.of(m.getLineCounter())));
            }
            classes.add(new ClassCoverage(name, Counter.of(cc.getInstructionCounter()), Counter.of(cc.getBranchCounter()),
                    Counter.of(cc.getLineCounter()), Counter.of(cc.getMethodCounter()), ms));
            ic += cc.getInstructionCounter().getCoveredCount(); it += cc.getInstructionCounter().getTotalCount();
            bc += cc.getBranchCounter().getCoveredCount(); bt += cc.getBranchCounter().getTotalCount();
            lc += cc.getLineCounter().getCoveredCount(); lt += cc.getLineCounter().getTotalCount();
            mc += cc.getMethodCounter().getCoveredCount(); mt += cc.getMethodCounter().getTotalCount();
        }
        if (htmlOut != null) writeHtml(bundle, loader, htmlOut, sourceRoots);
        return new Report(classes, new Counter(ic, it), new Counter(bc, bt), new Counter(lc, lt), new Counter(mc, mt));
    }

    private static void writeHtml(IBundleCoverage bundle, ExecFileLoader loader, Path out, List<Path> sourceRoots) throws IOException {
        Files.createDirectories(out);
        HTMLFormatter fmt = new HTMLFormatter();
        IReportVisitor visitor = fmt.createVisitor(new FileMultiReportOutput(out.toFile()));
        visitor.visitInfo(loader.getSessionInfoStore().getInfos(), loader.getExecutionDataStore().getContents());
        Path src = sourceRoots.isEmpty() ? null : sourceRoots.get(0);
        visitor.visitBundle(bundle, src == null ? new DirectorySourceFileLocator(out.toFile(), "utf-8", 4)
                : new DirectorySourceFileLocator(src.toFile(), "utf-8", 4));
        visitor.visitEnd();
    }
}
