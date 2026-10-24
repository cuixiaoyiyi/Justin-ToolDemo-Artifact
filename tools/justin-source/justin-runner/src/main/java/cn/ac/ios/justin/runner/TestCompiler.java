package cn.ac.ios.justin.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compiles generated test sources with a forked javac. A separate process keeps the compiler's heap
 * independent of whatever the generation sandbox left behind and survives compiler crashes.
 * If some files fail, they are dropped and the rest is compiled again once, so one bad file does not
 * lose the whole class set. Failures are reported per file as "line N: message" strings.
 */
public final class TestCompiler {
    private static final Logger log = LoggerFactory.getLogger(TestCompiler.class);
    private static final Pattern DIAG = Pattern.compile("^(.+?\\.java):(\\d+): error: (.*)$");

    public record Result(List<Path> compiled, Map<Path, List<String>> failed) {
        public boolean allCompiled() { return failed.isEmpty(); }
    }

    private final int release;
    private final String maxHeap;

    public TestCompiler(int release) { this(release, "2g"); }

    public TestCompiler(int release, String maxHeap) {
        this.release = release;
        this.maxHeap = maxHeap;
    }

    public Result compile(List<Path> sources, List<Path> classpath, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<Path> remaining = new ArrayList<>(sources);
        Map<Path, List<String>> failed = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 2 && !remaining.isEmpty(); attempt++) {
            Map<Path, List<String>> errors = compileOnce(remaining, classpath, outDir);
            if (errors.isEmpty()) return new Result(remaining, failed);
            failed.putAll(errors);
            remaining.removeAll(errors.keySet());
        }
        if (!remaining.isEmpty()) {
            Map<Path, List<String>> errors = compileOnce(remaining, classpath, outDir);
            if (!errors.isEmpty()) {
                failed.putAll(errors);
                remaining.removeAll(errors.keySet());
            }
        }
        return new Result(remaining, failed);
    }

    private Map<Path, List<String>> compileOnce(List<Path> sources, List<Path> classpath, Path outDir) throws IOException {
        Path argfile = Files.createTempFile("justin-javac", ".args");
        try {
            String cp = classpath.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            List<String> lines = new ArrayList<>();
            lines.add("--release"); lines.add(String.valueOf(release));
            lines.add("-d"); lines.add(quote(outDir.toAbsolutePath().toString()));
            lines.add("-cp"); lines.add(quote(cp));
            lines.add("-encoding"); lines.add("UTF-8");
            lines.add("-proc:none"); lines.add("-Xlint:none"); lines.add("-nowarn");
            lines.add("-Xmaxerrs"); lines.add("100000");
            for (Path s : sources) lines.add(quote(s.toAbsolutePath().toString()));
            Files.write(argfile, lines, StandardCharsets.UTF_8);

            List<String> cmd = List.of(javacExecutable(), "-J-Xmx" + maxHeap, "-J-Duser.language=en", "-J-Dfile.encoding=UTF-8", "@" + argfile.toAbsolutePath());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(20, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); throw new IOException("javac timed out"); }
            Map<Path, List<String>> errors = parse(out, sources);
            if (p.exitValue() != 0 && errors.isEmpty()) {
                log.warn("javac failed without per-file diagnostics:\n{}", out.length() > 4000 ? out.substring(0, 4000) : out);
                for (Path s : sources) errors.put(s, List.of("javac failed without file-level diagnostics: " + firstLine(out)));
            }
            return errors;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while compiling", e);
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    /** Maps javac's "path:line: error: message" (plus indented detail lines) back to the given source paths. */
    static Map<Path, List<String>> parse(String output, List<Path> sources) {
        Map<String, Path> byAbs = new LinkedHashMap<>();
        for (Path s : sources) byAbs.put(s.toAbsolutePath().normalize().toString(), s);
        Map<Path, List<String>> errors = new LinkedHashMap<>();
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DIAG.matcher(lines[i]);
            if (!m.matches()) continue;
            Path file = byAbs.get(Path.of(m.group(1)).toAbsolutePath().normalize().toString());
            if (file == null) continue;
            StringBuilder msg = new StringBuilder("line ").append(m.group(2)).append(": ").append(m.group(3));
            // detail lines ("symbol: ...", "location: ...") are indented; the source echo and caret line are skipped
            for (int j = i + 1; j < lines.length && lines[j].startsWith("  ") && !DIAG.matcher(lines[j]).matches(); j++) {
                String d = lines[j].strip();
                if (d.equals("^") || d.isEmpty()) continue;
                if (lines[j].startsWith("    ") && !d.startsWith("symbol") && !d.startsWith("location") && !d.startsWith("required") && !d.startsWith("found") && !d.startsWith("reason")) continue;
                msg.append(' ').append(d);
            }
            errors.computeIfAbsent(file, k -> new ArrayList<>()).add(msg.toString());
        }
        return errors;
    }

    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\") + "\""; }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    static String javacExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = bin.resolve(File.separatorChar == '\\' ? "javac.exe" : "javac");
        if (!Files.exists(exe)) throw new IllegalStateException("no javac at " + exe + ": run Justin on a JDK, not a JRE");
        return exe.toString();
    }
}
