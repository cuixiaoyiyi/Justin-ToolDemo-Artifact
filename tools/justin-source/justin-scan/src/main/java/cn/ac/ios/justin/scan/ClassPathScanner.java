package cn.ac.ios.justin.scan;

import cn.ac.ios.justin.core.target.ClassTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Enumerates class files in directories and jars and scans those whose binary name passes the filter. */
public final class ClassPathScanner {
    private static final Logger log = LoggerFactory.getLogger(ClassPathScanner.class);

    private ClassPathScanner() {}

    public static List<ClassTarget> scan(List<Path> roots, Predicate<String> nameFilter) throws IOException {
        List<ClassTarget> out = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) scanDirectory(root, nameFilter, out);
            else if (Files.isRegularFile(root) && root.toString().endsWith(".jar")) scanJar(root, nameFilter, out);
            else log.warn("ignoring classpath root {}", root);
        }
        return out;
    }

    private static void scanDirectory(Path root, Predicate<String> filter, List<ClassTarget> out) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                String rel = root.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - 6).replace('/', '.');
                if (name.equals("module-info") || name.endsWith(".package-info")) continue;
                if (!filter.test(name)) continue;
                try {
                    out.add(ClassScanner.scan(Files.readAllBytes(p)));
                } catch (RuntimeException e) {
                    log.warn("failed to scan {}: {}", p, e.toString());
                }
            }
        }
    }

    private static void scanJar(Path jar, Predicate<String> filter, List<ClassTarget> out) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                String name = n.substring(0, n.length() - 6).replace('/', '.');
                if (name.equals("module-info") || name.endsWith(".package-info")) continue;
                if (!filter.test(name)) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    out.add(ClassScanner.scan(in.readAllBytes()));
                } catch (RuntimeException ex) {
                    log.warn("failed to scan {}!{}: {}", jar, n, ex.toString());
                }
            }
        }
    }
}
