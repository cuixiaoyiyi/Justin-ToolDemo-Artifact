package cn.ac.ios.justin.api;

import cn.ac.ios.justin.core.config.GenerationConfig;
import cn.ac.ios.justin.core.scope.Scope;

import java.nio.file.Path;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Everything a front-end hands to the engine.
 * @param outputDir   base directory; every run gets its own subdirectory (see runDir) so earlier reports are kept
 * @param runLabel    optional name for the run directory; null means timestamp + strategies + seed
 * @param runTests    compile, execute in a forked JVM and measure coverage after generation
 */
public record GenerationRequest(ProjectModel project, Scope scope, GenerationConfig config, Path outputDir, String runLabel, boolean runTests, Path llmConfig,
                                cn.ac.ios.justin.symexec.SymConfig symbolic, int threads) {

    public GenerationRequest(ProjectModel project, Scope scope, GenerationConfig config, Path outputDir, String runLabel, boolean runTests, Path llmConfig,
                             cn.ac.ios.justin.symexec.SymConfig symbolic) {
        this(project, scope, config, outputDir, runLabel, runTests, llmConfig, symbolic, 1);
    }

    public GenerationRequest(ProjectModel project, Scope scope, GenerationConfig config, Path outputDir, boolean runTests) {
        this(project, scope, config, outputDir, null, runTests, null, null, 1);
    }

    public GenerationRequest(ProjectModel project, Scope scope, GenerationConfig config, Path outputDir, String runLabel, boolean runTests) {
        this(project, scope, config, outputDir, runLabel, runTests, null, null, 1);
    }

    public GenerationRequest(ProjectModel project, Scope scope, GenerationConfig config, Path outputDir, String runLabel, boolean runTests, Path llmConfig) {
        this(project, scope, config, outputDir, runLabel, runTests, llmConfig, null, 1);
    }

    /** outputDir/&lt;label&gt;; the default label is like 20260914-013005_random+special_seed42. */
    public Path runDir() {
        String label = runLabel;
        if (label == null || label.isBlank()) {
            label = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "_" + String.join("+", config.strategies()) + "_seed" + config.seed();
        }
        return outputDir.resolve(label);
    }

    public Path generatedSrcDir(Path runDir) { return runDir.resolve("generated-tests").resolve("src"); }
    public Path generatedClassesDir(Path runDir) { return runDir.resolve("generated-tests").resolve("classes"); }
    public Path reportsDir(Path runDir) { return runDir.resolve("reports"); }
}
