package cn.ac.ios.justin.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs compiled tests in a separate JVM, optionally with the JaCoCo agent attached, using the JUnit
 * Platform console launcher. Isolation protects Justin from System.exit, static state and native crashes.
 */
public final class ForkedTestRunner {
    private static final Logger log = LoggerFactory.getLogger(ForkedTestRunner.class);

    /**
     * @param jacocoExec    destination for coverage data, or null to run without the agent
     * @param selectMethods "pkg.Cls#method" selectors; empty means scan the whole test classes directory
     */
    public record Spec(Path testClassesDir, List<Path> subjectClasspath, Path reportsDir, Path jacocoExec,
                       List<String> jacocoIncludes, long timeoutMillis, Path logFile, List<String> selectMethods) {
        public Spec(Path testClassesDir, List<Path> subjectClasspath, Path reportsDir, Path jacocoExec,
                    List<String> jacocoIncludes, long timeoutMillis, Path logFile) {
            this(testClassesDir, subjectClasspath, reportsDir, jacocoExec, jacocoIncludes, timeoutMillis, logFile, List.of());
        }
    }

    public record Result(int exitCode, boolean timedOut, long millis) {}

    public Result run(Spec spec) throws IOException, InterruptedException {
        Files.createDirectories(spec.reportsDir());
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        if (spec.jacocoExec() != null) {
            Files.createDirectories(spec.jacocoExec().getParent());
            Files.deleteIfExists(spec.jacocoExec());
            String includes = spec.jacocoIncludes().isEmpty() ? "*" : String.join(":", spec.jacocoIncludes());
            cmd.add("-javaagent:" + ToolJars.jacocoAgent() + "=destfile=" + spec.jacocoExec() + ",includes=" + includes + ",output=file");
        }
        // no -Djava.awt.headless or -Dfile.encoding here: the forked JVM must see the same system
        // properties as the generation JVM, otherwise recorded values of property lookups differ
        cmd.add("-Xss4m");
        cmd.add("-cp");
        List<Path> cp = new ArrayList<>();
        cp.add(spec.testClassesDir());
        cp.addAll(spec.subjectClasspath());
        cp.add(ToolJars.junitConsoleStandalone());
        cmd.add(cp.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        cmd.add("org.junit.platform.console.ConsoleLauncher");
        cmd.add("execute");
        if (spec.selectMethods().isEmpty()) {
            cmd.add("--scan-classpath");
            cmd.add(spec.testClassesDir().toString());
        } else {
            for (String m : spec.selectMethods()) { cmd.add("--select-method"); cmd.add(m); }
        }
        cmd.add("--reports-dir");
        cmd.add(spec.reportsDir().toString());
        cmd.add("--details=summary");
        cmd.add("--disable-banner");
        cmd.add("--disable-ansi-colors");
        log.debug("forking: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Files.createDirectories(spec.logFile().getParent());
        pb.redirectOutput(spec.logFile().toFile());
        long t0 = System.currentTimeMillis();
        Process p = pb.start();
        boolean finished = p.waitFor(spec.timeoutMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            return new Result(-1, true, System.currentTimeMillis() - t0);
        }
        return new Result(p.exitValue(), false, System.currentTimeMillis() - t0);
    }

    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = bin.resolve(File.separatorChar == '\\' ? "java.exe" : "java");
        return exe.toString();
    }
}
