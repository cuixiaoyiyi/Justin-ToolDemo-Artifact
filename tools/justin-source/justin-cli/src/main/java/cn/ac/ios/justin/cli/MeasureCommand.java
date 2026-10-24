package cn.ac.ios.justin.cli;

import cn.ac.ios.justin.analysis.LoaderBenchmark;
import cn.ac.ios.justin.api.ProjectModel;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.scan.ClassPathScanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "measure", mixinStandardHelpOptions = true,
        description = "Measure SootUp on-demand loading cost (time, heap) for the classes in scope.")
public final class MeasureCommand implements Callable<Integer> {

    @Mixin CommonOptions common;

    @Option(names = "--minimal", description = "Use no body interceptors (raw Jimple)")
    boolean minimal;

    @Option(names = "--repeat", defaultValue = "1", description = "Repeat the measurement N times (later runs show warmed-up cost)")
    int repeat;

    @Override public Integer call() throws Exception {
        ProjectModel project = common.projectModel();
        long t0 = System.currentTimeMillis();
        List<ClassTarget> all = ClassPathScanner.scan(project.targetRoots(), n -> true);
        long scanMs = System.currentTimeMillis() - t0;
        List<ClassTarget> targets = all.stream().filter(common.scope()::accepts).toList();
        System.out.printf("ASM scan: %d classes in %d ms; %d in scope%n", all.size(), scanMs, targets.size());
        for (int i = 0; i < repeat; i++) {
            LoaderBenchmark.Result r = LoaderBenchmark.run(project.targetRoots(), project.dependencyClasspath(), targets, minimal);
            System.out.printf("SootUp run %d (%s): view %d ms; %d classes, %d methods, bodies built %d, failed %d, %d stmts in %d ms; "
                            + "heap before %.1f MB, after %.1f MB, retained %.1f MB; cached classes %d%n",
                    i + 1, minimal ? "minimal interceptors" : "default interceptors", r.viewMillis(), r.classes(), r.methods(), r.bodiesBuilt(), r.bodiesFailed(),
                    r.stmts(), r.bodiesMillis(), r.heapBeforeBytes() / 1e6, r.heapAfterBytes() / 1e6, r.retainedBytes() / 1e6, r.cachedClasses());
        }
        return 0;
    }
}
