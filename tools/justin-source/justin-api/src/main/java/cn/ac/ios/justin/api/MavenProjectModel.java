package cn.ac.ios.justin.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven project, single- or multi-module. One {@code dependency:build-classpath} run over the reactor
 * writes every module's runtime classpath; the reactor's modules are read from the poms (nested
 * aggregators included) and ordered by their dependencies on each other. The aggregate model exposes
 * all modules' classes as target roots; {@link #module(String)} restricts to one module, whose
 * classpath also contains the classes of the sibling modules it depends on.
 */
public final class MavenProjectModel implements ProjectModel {
    private static final Logger log = LoggerFactory.getLogger(MavenProjectModel.class);

    /** One buildable module of the reactor (or the project itself when it has no modules). */
    public record Module(String name, Path dir, List<Path> dependencies, List<String> siblingDependencies, int javaVersion) {
        public Path classes() { return dir.resolve("target").resolve("classes"); }
        public Path sources() { return dir.resolve("src").resolve("main").resolve("java"); }
    }

    private final String name;
    private final Path dir;
    private final List<Module> modules;
    private final int javaVersion;

    private MavenProjectModel(String name, Path dir, List<Module> modules) {
        this.name = name;
        this.dir = dir;
        this.modules = modules;
        this.javaVersion = modules.stream().mapToInt(Module::javaVersion).min().orElse(8);
    }

    public static boolean isMavenProject(Path dir) { return Files.exists(dir.resolve("pom.xml")); }

    /** @param mvn path to the mvn executable, or null to use "mvn" from PATH */
    public static MavenProjectModel detect(Path dir, Path mvn) throws IOException {
        dir = dir.toAbsolutePath().normalize();
        String rootPom = Files.readString(dir.resolve("pom.xml"));
        List<Path> moduleDirs = leafModules(dir, rootPom);
        List<Path> built = moduleDirs.stream().filter(d -> Files.isDirectory(d.resolve("target").resolve("classes"))).toList();
        if (built.isEmpty()) {
            throw new IOException("no target/classes under " + dir + (moduleDirs.size() > 1 ? " or its modules" : "") + ": build the project first (mvn compile)");
        }
        runBuildClasspath(dir, mvn);
        int rootVersion = detectJavaVersion(rootPom);
        Map<Path, String> artifactOf = new LinkedHashMap<>();
        Map<Path, String> pomOf = new LinkedHashMap<>();
        for (Path d : built) {
            String pom = Files.readString(d.resolve("pom.xml"));
            pomOf.put(d, pom);
            String art = artifactId(pom);
            artifactOf.put(d, art == null ? d.getFileName().toString() : art);
        }
        List<Module> modules = new ArrayList<>();
        for (Path d : built) {
            String pom = pomOf.get(d);
            List<Path> deps = readClasspath(d.resolve("target").resolve("justin-classpath.txt"));
            List<String> siblings = new ArrayList<>();
            Matcher m = Pattern.compile("<dependency>.*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>.*?</dependency>", Pattern.DOTALL).matcher(pom);
            while (m.find()) if (artifactOf.containsValue(m.group(1)) && !m.group(1).equals(artifactOf.get(d))) siblings.add(m.group(1));
            int version = pom.contains("maven.compiler") || pom.contains("<release>") || pom.contains("<target>") || pom.contains("java.version") ? detectJavaVersion(pom) : rootVersion;
            modules.add(new Module(artifactOf.get(d), d, deps, siblings, version));
        }
        modules = ordered(modules);
        String name = artifactId(rootPom);
        log.info("Maven project {}: {} module(s) {}, Java {}", name, modules.size(), modules.stream().map(Module::name).toList(),
                modules.stream().mapToInt(Module::javaVersion).min().orElse(8));
        return new MavenProjectModel(name == null ? dir.getFileName().toString() : name, dir, modules);
    }

    /** Directories of the modules that actually build classes: the project itself, or the leaves of the (nested) reactor. */
    private static List<Path> leafModules(Path dir, String pom) throws IOException {
        List<String> children = new ArrayList<>();
        Matcher m = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>").matcher(pom);
        while (m.find()) children.add(m.group(1));
        if (children.isEmpty()) return List.of(dir);
        List<Path> out = new ArrayList<>();
        for (String c : children) {
            Path cd = dir.resolve(c).normalize();
            Path cp = cd.resolve("pom.xml");
            if (!Files.exists(cp)) continue;
            out.addAll(leafModules(cd, Files.readString(cp)));
        }
        // an aggregator with its own sources (rare) counts too
        if (Files.isDirectory(dir.resolve("src").resolve("main").resolve("java"))) out.add(0, dir);
        return out;
    }

    private static void runBuildClasspath(Path dir, Path mvn) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn == null ? (File.separatorChar == '\\' ? "mvn.cmd" : "mvn") : mvn.toString());
        cmd.add("-q");
        cmd.add("-B");
        // the compile phase makes reactor siblings resolve to their target/classes instead of installed artifacts
        cmd.add("compile");
        cmd.add("dependency:build-classpath");
        cmd.add("-Dmdep.outputFile=target/justin-classpath.txt");
        cmd.add("-Dmdep.includeScope=runtime");
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IOException("mvn dependency:build-classpath failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running Maven", e);
        }
    }

    private static List<Path> readClasspath(Path cpFile) throws IOException {
        List<Path> deps = new ArrayList<>();
        if (Files.exists(cpFile)) {
            String cp = Files.readString(cpFile).trim();
            if (!cp.isEmpty()) for (String s : cp.split(File.pathSeparator)) if (!s.isBlank()) deps.add(Path.of(s.trim()));
        }
        return deps;
    }

    /** Dependency order: a module comes after the sibling modules it depends on (cycles broken by declaration order). */
    static List<Module> ordered(List<Module> modules) {
        List<Module> out = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        List<Module> remaining = new ArrayList<>(modules);
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (var it = remaining.iterator(); it.hasNext(); ) {
                Module m = it.next();
                boolean ready = m.siblingDependencies().stream().allMatch(s -> placed.contains(s) || modules.stream().noneMatch(x -> x.name().equals(s)));
                if (ready) { out.add(m); placed.add(m.name()); it.remove(); progress = true; }
            }
            if (!progress) { Module m = remaining.remove(0); out.add(m); placed.add(m.name()); }
        }
        return out;
    }

    static int detectJavaVersion(String pom) {
        for (String key : List.of("maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version")) {
            String v = extract(pom, "<" + Pattern.quote(key) + ">\\s*(?:1\\.)?(\\d+)\\s*</" + Pattern.quote(key) + ">");
            if (v != null) return Integer.parseInt(v);
        }
        String v = extract(pom, "<release>\\s*(\\d+)\\s*</release>");
        if (v == null) v = extract(pom, "<target>\\s*(?:1\\.)?(\\d+)\\s*</target>");
        return v != null ? Integer.parseInt(v) : 8;
    }

    /** The project's own artifactId (the first one outside a parent/dependency block). */
    static String artifactId(String pom) {
        String stripped = pom.replaceAll("(?s)<parent>.*?</parent>", "").replaceAll("(?s)<dependencies>.*?</dependencies>", "")
                .replaceAll("(?s)<build>.*?</build>", "").replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        return extract(stripped, "<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    }

    private static String extract(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // ---------- ProjectModel ----------

    @Override public String name() { return name; }
    public Path dir() { return dir; }
    public List<Module> mavenModules() { return modules; }

    @Override public List<Path> targetRoots() { return modules.stream().map(Module::classes).toList(); }

    @Override public List<Path> dependencyClasspath() {
        Set<Path> roots = new LinkedHashSet<>(targetRoots());
        Set<Path> out = new LinkedHashSet<>();
        for (Module m : modules) for (Path p : m.dependencies()) if (!roots.contains(p.toAbsolutePath().normalize())) out.add(p);
        return new ArrayList<>(out);
    }

    @Override public List<Path> sourceRoots() {
        return modules.stream().map(Module::sources).filter(Files::isDirectory).toList();
    }

    @Override public int javaVersion() { return javaVersion; }

    @Override public List<ProjectModel> modules() {
        if (modules.size() <= 1) return List.of(this);
        return modules.stream().map(m -> (ProjectModel) moduleModel(m)).toList();
    }

    @Override public Optional<ProjectModel> module(String moduleName) {
        return modules.stream().filter(m -> m.name().equals(moduleName) || m.dir().getFileName().toString().equals(moduleName)).findFirst().map(this::moduleModel);
    }

    /** One module: its classes as the only target root; its dependencies plus the classes of the siblings it (transitively) depends on. */
    private ProjectModel moduleModel(Module m) {
        Set<Path> deps = new LinkedHashSet<>();
        for (String s : transitiveSiblings(m)) modules.stream().filter(x -> x.name().equals(s)).findFirst().ifPresent(x -> deps.add(x.classes()));
        for (Path p : m.dependencies()) if (!p.equals(m.classes())) deps.add(p);
        List<Path> sources = Files.isDirectory(m.sources()) ? List.of(m.sources()) : List.of();
        return new ClasspathProjectModel(m.name(), List.of(m.classes()), new ArrayList<>(deps), sources, m.javaVersion());
    }

    private Set<String> transitiveSiblings(Module m) {
        Set<String> out = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(m.siblingDependencies());
        while (!todo.isEmpty()) {
            String s = todo.remove(0);
            if (!out.add(s)) continue;
            modules.stream().filter(x -> x.name().equals(s)).findFirst().ifPresent(x -> todo.addAll(x.siblingDependencies()));
        }
        return out;
    }
}
