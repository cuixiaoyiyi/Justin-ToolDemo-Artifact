package cn.ac.ios.justin.analysis;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;

import java.nio.file.Path;
import java.util.List;

/**
 * Measures what on-demand loading actually costs: wall time and retained heap for creating a view,
 * resolving the requested classes and building every method body in them. Used by the CLI
 * "measure" command so the P0 numbers are real, not estimated.
 */
public final class LoaderBenchmark {

    public record Result(int classes, int methods, int bodiesBuilt, int bodiesFailed, long stmts,
                         long viewMillis, long bodiesMillis, long heapBeforeBytes, long heapAfterBytes, int cachedClasses) {
        public long retainedBytes() { return Math.max(0, heapAfterBytes - heapBeforeBytes); }
    }

    private LoaderBenchmark() {}

    public static Result run(List<Path> targetRoots, List<Path> deps, List<ClassTarget> classes, boolean minimalInterceptors) {
        long heapBefore = usedHeapAfterGc();
        long t0 = System.nanoTime();
        SootUpLoader loader = minimalInterceptors ? SootUpLoader.minimal(targetRoots, deps) : SootUpLoader.withDefaults(targetRoots, deps);
        long t1 = System.nanoTime();
        FactExtractor fx = new FactExtractor(loader);
        int methods = 0, built = 0, failed = 0;
        long stmts = 0;
        for (ClassTarget c : classes) {
            for (MethodTarget m : c.methods()) {
                if (m.isAbstract() || m.isNative()) continue;
                methods++;
                MethodFacts f = fx.extract(m);
                if (f.bodyAvailable()) { built++; stmts += f.stmtCount(); } else failed++;
            }
        }
        long t2 = System.nanoTime();
        long heapAfter = usedHeapNoGc();
        int cached = loader.cachedClasses();
        loader.close();
        return new Result(classes.size(), methods, built, failed, stmts,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, heapBefore, heapAfter, cached);
    }

    private static long usedHeapAfterGc() {
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return usedHeapNoGc();
    }

    private static long usedHeapNoGc() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
