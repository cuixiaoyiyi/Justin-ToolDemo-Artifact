package cn.ac.ios.justin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Gradle project, single- or multi-project. An init script registers a {@code justinClasspath} task
 * in every project that applies the Java plugin; one Gradle invocation compiles the main classes and
 * prints, per project, the classes directories, the runtime classpath, the source directories, the
 * sibling projects it depends on and the target Java version. The wrapper is preferred when present.
 */
public final class GradleProjectModel implements ProjectModel {
    private static final Logger log = LoggerFactory.getLogger(GradleProjectModel.class);

    private static final String INIT_SCRIPT = """
            allprojects { p ->
              p.tasks.register('justinClasspath') {
                doLast {
                  if (p.plugins.hasPlugin('java')) {
                    def ss = p.sourceSets.main
                    def classes = ss.output.classesDirs.files.join(File.pathSeparator)
                    def cp = ''
                    try { cp = p.configurations.runtimeClasspath.files.join(File.pathSeparator) } catch (Throwable t) { cp = '' }
                    def src = ss.java.srcDirs.join(File.pathSeparator)
                    def siblings = []
                    try {
                      p.configurations.runtimeClasspath.allDependencies.each { d ->
                        if (d instanceof org.gradle.api.artifacts.ProjectDependency) {
                          try { siblings << d.path } catch (Throwable t) { siblings << d.dependencyProject.path }
                        }
                      }
                    } catch (Throwable t) { }
                    def rel = '8'
                    try {
                      def lv = p.java.toolchain.languageVersion.orNull
                      if (lv != null) rel = lv.asInt().toString()
                      else if (p.java.targetCompatibility != null) rel = p.java.targetCompatibility.majorVersion
                    } catch (Throwable t) { }
                    println 'JUSTIN|' + p.path + '|' + p.name + '|' + p.projectDir + '|' + classes + '|' + cp + '|' + src + '|' + siblings.join(',') + '|' + rel
                  }
                }
              }
            }
            """;

    /** One Java project of the build. */
    public record Module(String path, String name, Path dir, List<Path> classesDirs, List<Path> dependencies, List<Path> sourceDirs,
                         List<String> siblingPaths, int javaVersion) {}

    private final String name;
    private final Path dir;
    private final List<Module> modules;
    private final int javaVersion;

    private GradleProjectModel(String name, Path dir, List<Module> modules) {
        this.name = name;
        this.dir = dir;
        this.modules = modules;
        this.javaVersion = modules.stream().mapToInt(Module::javaVersion).min().orElse(8);
    }

    public static boolean isGradleProject(Path dir) {
        for (String f : List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")) if (Files.exists(dir.resolve(f))) return true;
        return false;
    }

    /** @param gradle path to the gradle executable, or null to use the project's wrapper, else "gradle" from PATH */
    public static GradleProjectModel detect(Path dir, Path gradle) throws IOException {
        dir = dir.toAbsolutePath().normalize();
        Path init = Files.createTempFile("justin-init", ".gradle");
        Files.writeString(init, INIT_SCRIPT, StandardCharsets.UTF_8);
        List<String> cmd = new ArrayList<>();
        cmd.add(gradleCommand(dir, gradle));
        cmd.add("-q");
        cmd.add("--console=plain");
        cmd.add("--init-script");
        cmd.add(init.toString());
        cmd.add("classes");
        cmd.add("justinClasspath");
        String out;
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IOException("gradle classes justinClasspath failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running Gradle", e);
        } finally {
            Files.deleteIfExists(init);
        }
        List<Module> modules = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.startsWith("JUSTIN|")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 9) continue;
            List<Path> classes = paths(f[4]).stream().filter(Files::isDirectory).toList();
            if (classes.isEmpty()) continue;
            int version;
            try { version = Integer.parseInt(f[8].trim().replace("1.", "")); } catch (NumberFormatException e) { version = 8; }
            List<String> siblings = f[7].isBlank() ? List.of() : List.of(f[7].split(","));
            modules.add(new Module(f[1], f[2], Path.of(f[3]), classes, paths(f[5]), paths(f[6]).stream().filter(Files::isDirectory).toList(), siblings, version));
        }
        if (modules.isEmpty()) throw new IOException("no Java project with compiled classes found under " + dir + " (gradle output: " + out.strip() + ")");
        modules = ordered(modules);
        String name = dir.getFileName().toString();
        Path settings = Files.exists(dir.resolve("settings.gradle")) ? dir.resolve("settings.gradle") : dir.resolve("settings.gradle.kts");
        if (Files.exists(settings)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(Files.readString(settings));
            if (m.find()) name = m.group(1);
        }
        log.info("Gradle project {}: {} project(s) {}, Java {}", name, modules.size(), modules.stream().map(Module::path).toList(),
                modules.stream().mapToInt(Module::javaVersion).min().orElse(8));
        return new GradleProjectModel(name, dir, modules);
    }

    private static String gradleCommand(Path dir, Path gradle) {
        if (gradle != null) return gradle.toString();
        boolean windows = File.separatorChar == '\\';
        Path wrapper = dir.resolve(windows ? "gradlew.bat" : "gradlew");
        if (Files.exists(wrapper)) return wrapper.toString();
        return windows ? "gradle.bat" : "gradle";
    }

    private static List<Path> paths(String joined) {
        List<Path> out = new ArrayList<>();
        if (joined == null || joined.isBlank()) return out;
        for (String s : joined.split(java.util.regex.Pattern.quote(File.pathSeparator))) if (!s.isBlank()) out.add(Path.of(s.trim()));
        return out;
    }

    /** Dependency order: a project comes after the sibling projects it depends on. */
    static List<Module> ordered(List<Module> modules) {
        List<Module> out = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        List<Module> remaining = new ArrayList<>(modules);
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (var it = remaining.iterator(); it.hasNext(); ) {
                Module m = it.next();
                boolean ready = m.siblingPaths().stream().allMatch(s -> placed.contains(s) || modules.stream().noneMatch(x -> x.path().equals(s)));
                if (ready) { out.add(m); placed.add(m.path()); it.remove(); progress = true; }
            }
            if (!progress) { Module m = remaining.remove(0); out.add(m); placed.add(m.path()); }
        }
        return out;
    }

    // ---------- ProjectModel ----------

    @Override public String name() { return name; }
    public Path dir() { return dir; }
    public List<Module> gradleModules() { return modules; }

    @Override public List<Path> targetRoots() {
        List<Path> out = new ArrayList<>();
        for (Module m : modules) out.addAll(m.classesDirs());
        return out;
    }

    @Override public List<Path> dependencyClasspath() {
        Set<Path> roots = new LinkedHashSet<>(targetRoots());
        Set<Path> out = new LinkedHashSet<>();
        for (Module m : modules) for (Path p : m.dependencies()) if (!roots.contains(p) && !isSiblingArtifact(p)) out.add(p);
        return new ArrayList<>(out);
    }

    /** Sibling projects resolve to build/libs jars on the runtime classpath; their classes directories are used instead. */
    private boolean isSiblingArtifact(Path p) {
        for (Module m : modules) if (p.startsWith(m.dir().resolve("build").resolve("libs"))) return true;
        return false;
    }

    @Override public List<Path> sourceRoots() {
        List<Path> out = new ArrayList<>();
        for (Module m : modules) out.addAll(m.sourceDirs());
        return out;
    }

    @Override public int javaVersion() { return javaVersion; }

    @Override public List<ProjectModel> modules() {
        if (modules.size() <= 1) return List.of(this);
        return modules.stream().map(m -> (ProjectModel) moduleModel(m)).toList();
    }

    @Override public Optional<ProjectModel> module(String moduleName) {
        return modules.stream().filter(m -> m.name().equals(moduleName) || m.path().equals(moduleName) || m.path().equals(":" + moduleName)).findFirst().map(this::moduleModel);
    }

    private ProjectModel moduleModel(Module m) {
        Set<Path> deps = new LinkedHashSet<>();
        for (String s : transitiveSiblings(m)) modules.stream().filter(x -> x.path().equals(s)).findFirst().ifPresent(x -> deps.addAll(x.classesDirs()));
        for (Path p : m.dependencies()) if (!m.classesDirs().contains(p) && !isSiblingArtifact(p)) deps.add(p);
        return new ClasspathProjectModel(m.name(), m.classesDirs(), new ArrayList<>(deps), m.sourceDirs(), m.javaVersion());
    }

    private Set<String> transitiveSiblings(Module m) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(m.siblingPaths());
        while (!todo.isEmpty()) {
            String s = todo.remove(0);
            if (!out.add(s)) continue;
            modules.stream().filter(x -> x.path().equals(s)).findFirst().ifPresent(x -> todo.addAll(x.siblingPaths()));
        }
        return out;
    }
}
