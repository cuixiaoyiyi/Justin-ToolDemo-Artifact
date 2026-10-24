package cn.ac.ios.justin.cli;

import cn.ac.ios.justin.api.ClasspathProjectModel;
import cn.ac.ios.justin.api.GradleProjectModel;
import cn.ac.ios.justin.api.MavenProjectModel;
import cn.ac.ios.justin.api.ProjectModel;
import cn.ac.ios.justin.core.scope.Scope;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Input and scope options shared by subcommands. */
public class CommonOptions {

    @Option(names = "--project", description = "Maven or Gradle project directory (single- or multi-module); Maven needs target/classes, Gradle is compiled by the wrapper or --gradle")
    Path project;

    @Option(names = "--module", description = "With --project: generate for this module only (artifactId or directory name); default: all modules")
    String module;

    @Option(names = "--gradle", description = "Path to the gradle executable used with a Gradle --project (default: the project's wrapper, else gradle on PATH)")
    Path gradle;

    @Option(names = "--mvn", description = "Path to the mvn executable used with --project (default: mvn on PATH)")
    Path mvn;

    @Spec(Spec.Target.MIXEE) CommandSpec spec;

    @Option(names = "--maven", description = "Subject as a Maven artifact: group:artifact:version (or group:artifact for the latest release), a link to its jar, pom or version directory in a repository, or its page on mvnrepository.com / central.sonatype.com. The jar, its sources jar and its dependencies are downloaded to ~/.justin/repository")
    String maven;

    @Option(names = "--maven-repo", split = ",", description = "With --maven: repositories to try first (default: the repository of the link, Maven Central, then a mirror); also JUSTIN_MAVEN_REPO")
    List<String> mavenRepos = new ArrayList<>();

    @Option(names = "--no-maven-deps", negatable = false, description = "With --maven: do not resolve and download the artifact's dependencies")
    boolean noMavenDeps;

    @Option(names = "--classes", split = "${sys:path.separator}", description = "Class directories or jars to generate tests for (alternative to --project)")
    List<Path> classes = new ArrayList<>();

    @Option(names = "--cp", split = "${sys:path.separator}", description = "Dependency classpath entries (jars or directories)")
    List<Path> cp = new ArrayList<>();

    @Option(names = "--sources", split = "${sys:path.separator}", description = "Source directories or sources jars of the subject (optional: source view in the HTML report, snippets for the LLM)")
    List<Path> sources = new ArrayList<>();

    @Option(names = "--java-version", description = "Java language level of the subject (default: from the pom, else read off the class files)")
    Integer javaVersion;

    @Option(names = "--class", description = "Generate for one class (binary name, e.g. org.apache.commons.lang3.StringUtils)")
    String clazz;

    @Option(names = "--method", description = "Generate for one method: pkg.Cls#name or pkg.Cls#name(int,java.lang.String)")
    String method;

    @Option(names = "--package", description = "Generate for a package")
    String pkg;

    @Option(names = "--no-recursive", negatable = false, description = "With --package: exclude sub-packages")
    boolean noRecursive;

    @Option(names = "--include", split = ",", description = "Class name globs to include (** across packages)")
    List<String> includes = new ArrayList<>();

    @Option(names = "--exclude", split = ",", description = "Class name globs to exclude")
    List<String> excludes = new ArrayList<>();

    ProjectModel projectModel() throws IOException {
        if (project != null) {
            ProjectModel whole;
            List<String> names;
            if (MavenProjectModel.isMavenProject(project)) {
                MavenProjectModel mp = MavenProjectModel.detect(project, mvn);
                whole = mp;
                names = mp.mavenModules().stream().map(MavenProjectModel.Module::name).toList();
            } else if (GradleProjectModel.isGradleProject(project)) {
                GradleProjectModel gp = GradleProjectModel.detect(project, gradle);
                whole = gp;
                names = gp.gradleModules().stream().map(GradleProjectModel.Module::path).toList();
            } else {
                throw new IOException("no pom.xml or Gradle build script in " + project);
            }
            ProjectModel m = whole;
            if (module != null) {
                m = whole.module(module).orElseThrow(() -> new IOException("no module '" + module + "' in " + project + "; modules: " + String.join(", ", names)));
            }
            if (javaVersion == null) return m;
            return new ClasspathProjectModel(m.name(), m.targetRoots(), m.dependencyClasspath(), m.sourceRoots(), javaVersion);
        }
        List<Path> classRoots = new ArrayList<>(classes), classpath = new ArrayList<>(cp), sourceEntries = new ArrayList<>(sources);
        String name = null;
        if (maven != null) {
            cn.ac.ios.justin.api.MavenArtifacts.Resolved r = new cn.ac.ios.justin.api.MavenArtifacts(mavenRepos, null).resolve(maven, !noMavenDeps);
            classRoots.add(0, r.jar());
            classpath.addAll(r.dependencies());
            if (r.sources() != null) sourceEntries.add(r.sources());
            name = r.coordinates().artifactId() + "-" + r.coordinates().version();
            System.out.printf("[justin] maven: %s, %d dependencies, sources %s%n", r.coordinates().gav(), r.dependencies().size(), r.sources() == null ? "not available" : "yes");
            if (!r.unresolved().isEmpty()) System.out.println("[justin] maven: not resolved (classes that need them will fail to load): " + String.join(", ", r.unresolved()));
        }
        if (classRoots.isEmpty()) throw new IOException("give --project, --classes or --maven");
        for (Path c : classRoots) if (!java.nio.file.Files.exists(c)) throw new IOException("no such jar or class directory: " + c);
        if (name == null) name = classRoots.get(0).getFileName().toString().replace(".jar", "");
        List<Path> sourceRoots = new ArrayList<>();
        for (Path s : sourceEntries) sourceRoots.add(cn.ac.ios.justin.api.SubjectInputs.sourceRoot(s));
        int level = javaVersion != null ? javaVersion : cn.ac.ios.justin.api.SubjectInputs.javaLevel(classRoots);
        if (level > Runtime.version().feature()) {
            throw new IOException("the subject is compiled for Java " + level + " but Justin is running on Java " + Runtime.version().feature() + ": run it with a JDK " + level + " or newer");
        }
        if (javaVersion == null) System.out.println("[justin] Java level of the subject: " + level + " (from its class files; override with --java-version)");
        return new ClasspathProjectModel(name, classRoots, classpath, sourceRoots, level);
    }

    Scope scope() {
        Scope s;
        if (method != null) {
            if (!method.contains("#") || method.startsWith("#")) {
                throw new picocli.CommandLine.ParameterException(spec.commandLine(), "--method expects pkg.Cls#name or pkg.Cls#name(int,java.lang.String), got: " + method);
            }
            s = Scope.method(method);
        }
        else if (clazz != null) s = Scope.cls(clazz);
        else if (pkg != null) s = Scope.pkg(pkg, !noRecursive);
        else s = Scope.project();
        return s.withFilters(includes, excludes);
    }

    static String pathSep() { return File.pathSeparator; }
}
