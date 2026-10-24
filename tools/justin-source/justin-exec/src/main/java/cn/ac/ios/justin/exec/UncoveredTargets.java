package cn.ac.ios.justin.exec;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.internal.data.CRC64;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Maps the probes hit in the sandbox back to lines and branches of a class, and lists what the
 * kept tests leave uncovered: the targets a directed strategy (symbolic execution, P3) will aim at.
 */
public final class UncoveredTargets {

    /** One uncovered spot: a method, a source line, and how many of the line's branches are still missed. */
    public record Target(String method, String descriptor, int line, int missedBranches, int totalBranches, boolean lineUncovered) {}

    public record ClassSummary(String binaryName, int coveredLines, int totalLines, int coveredBranches, int totalBranches, List<Target> targets) {}

    private UncoveredTargets() {}

    /**
     * @param originalBytes the class as on disk (the probe ids were computed from these bytes)
     * @param probes        union of probe hits of the kept tests, or null if the class was never touched
     */
    public static ClassSummary analyze(String binaryName, byte[] originalBytes, BitSet probes, int maxTargets) throws IOException {
        return analyze(binaryName, originalBytes, probes, 0, maxTargets);
    }

    /**
     * @param probeCount number of probes JaCoCo placed in the class (from the in-process runtime), or 0 if unknown;
     *                   the analyzer rejects execution data whose length differs from its own probe count
     */
    public static ClassSummary analyze(String binaryName, byte[] originalBytes, BitSet probes, int probeCount, int maxTargets) throws IOException {
        ExecutionDataStore store = new ExecutionDataStore();
        long id = CRC64.classId(originalBytes);
        // the store needs the probe count; a class we never executed contributes an all-false array of
        // unknown length, so we let the analyzer see "no data" for it (everything uncovered)
        if (probes != null) {
            boolean[] arr = new boolean[Math.max(probeCount > 0 ? probeCount : probes.length(), 1)];
            for (int i = probes.nextSetBit(0); i >= 0; i = probes.nextSetBit(i + 1)) arr[i] = true;
            store.put(new ExecutionData(id, binaryName.replace('.', '/'), arr));
        }
        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(store, builder);
        try {
            analyzer.analyzeClass(originalBytes, binaryName);
        } catch (IllegalStateException e) {
            // probe array length mismatch (class changed?): report as unknown
            return new ClassSummary(binaryName, 0, 0, 0, 0, List.of());
        } catch (IOException e) {
            if (!(e.getCause() instanceof IllegalStateException)) throw e;
            // probe array length mismatch (class changed?): report as unknown
            return new ClassSummary(binaryName, 0, 0, 0, 0, List.of());
        }
        for (IClassCoverage cc : builder.getClasses()) {
            List<Target> targets = new ArrayList<>();
            for (IMethodCoverage m : cc.getMethods()) {
                for (int line = m.getFirstLine(); line <= m.getLastLine() && line >= 0; line++) {
                    ILine l = m.getLine(line);
                    int missedB = l.getBranchCounter().getMissedCount();
                    boolean lineMissed = l.getStatus() == ICounter.NOT_COVERED;
                    if (missedB > 0 || lineMissed) {
                        targets.add(new Target(m.getName(), m.getDesc(), line, missedB, l.getBranchCounter().getTotalCount(), lineMissed));
                        if (targets.size() >= maxTargets) break;
                    }
                }
                if (targets.size() >= maxTargets) break;
            }
            return new ClassSummary(binaryName, cc.getLineCounter().getCoveredCount(), cc.getLineCounter().getTotalCount(),
                    cc.getBranchCounter().getCoveredCount(), cc.getBranchCounter().getTotalCount(), targets);
        }
        return new ClassSummary(binaryName, 0, 0, 0, 0, List.of());
    }
}
