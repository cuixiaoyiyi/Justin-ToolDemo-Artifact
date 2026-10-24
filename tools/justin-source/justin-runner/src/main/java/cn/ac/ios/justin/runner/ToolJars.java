package cn.ac.ios.justin.runner;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;

/**
 * Locates the jars the forked test JVM needs: the JUnit console launcher and the JaCoCo agent. With
 * the thin CLI jar they are separate files in lib/; inside the single-file distribution (shaded jar)
 * they are embedded as resources under META-INF/justin-tools/ and extracted once to a cache directory.
 */
public final class ToolJars {
    private ToolJars() {}

    private static final String EMBEDDED_DIR = "META-INF/justin-tools/";

    public static Path junitConsoleStandalone() {
        return locate("org.junit.platform.console.ConsoleLauncher", "junit-platform-console-standalone.jar")
                .orElseThrow(() -> new IllegalStateException("junit-platform-console-standalone jar not found on Justin's classpath"));
    }

    public static Path jacocoAgent() {
        return locate("org.jacoco.agent.rt.RT", "org.jacoco.agent-runtime.jar")
                .orElseThrow(() -> new IllegalStateException("org.jacoco.agent (runtime) jar not found on Justin's classpath"));
    }

    /** The separate jar on the classpath, unless that "jar" is the shaded distribution itself: then the embedded copy. */
    static Optional<Path> locate(String binaryName, String embeddedName) {
        Optional<Path> own = fromClass(ToolJars.class.getName());
        Optional<Path> found = fromClass(binaryName);
        boolean shaded = found.isPresent() && own.isPresent() && found.get().equals(own.get());
        if (!shaded && found.isPresent()) return found;
        return extractEmbedded(embeddedName);
    }

    /** Copies META-INF/justin-tools/&lt;name&gt; to a per-version cache directory (once) and returns it. */
    static synchronized Optional<Path> extractEmbedded(String name) {
        try {
            java.net.URL res = ToolJars.class.getClassLoader().getResource(EMBEDDED_DIR + name);
            if (res == null) return Optional.empty();
            java.net.URLConnection conn = res.openConnection();
            conn.setUseCaches(false);
            long size = conn.getContentLengthLong();
            String key = Integer.toHexString((name + "|" + size).hashCode());
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "justin-tools", key);
            Path target = dir.resolve(name);
            if (Files.isRegularFile(target) && Files.size(target) == size) return Optional.of(target);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, name, ".part");
            try (java.io.InputStream in = conn.getInputStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                if (!Files.isRegularFile(target)) Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tmp);
            }
            return Optional.of(target);
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** junit-jupiter-api etc. are inside the standalone jar, so generated tests compile against it alone. */
    public static Path junitApiForCompilation() { return junitConsoleStandalone(); }

    static Optional<Path> fromClass(String binaryName) {
        try {
            Class<?> c = Class.forName(binaryName, false, ToolJars.class.getClassLoader());
            CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return Optional.empty();
            Path p = Path.of(cs.getLocation().toURI());
            return Files.exists(p) ? Optional.of(p) : Optional.empty();
        } catch (ClassNotFoundException | URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
