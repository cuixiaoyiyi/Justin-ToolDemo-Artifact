package cn.ac.ios.justin.api;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Conveniences for subjects given as files: a sources jar instead of a source directory, the Java level read off the class files. */
public final class SubjectInputs {
    private SubjectInputs() {}

    /**
     * A source root for the entry: a directory as it is; a sources jar or zip unpacked (once) under
     * {@code ~/.justin/sources}, keyed by name, size and modification time.
     */
    public static Path sourceRoot(Path entry) throws IOException {
        if (Files.isDirectory(entry)) return entry;
        if (!Files.isRegularFile(entry)) throw new IOException("no such source directory or sources jar: " + entry);
        String key = entry.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + Files.size(entry) + "-" + Files.getLastModifiedTime(entry).toMillis();
        Path dir = Path.of(System.getProperty("user.home", "."), ".justin", "sources", key);
        Path done = dir.resolve(".unpacked");
        if (Files.exists(done)) return dir;
        Files.createDirectories(dir);
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path target = dir.resolve(e.getName()).normalize();
                if (!target.startsWith(dir)) continue;        // entries that would escape the directory
                if (e.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(done, entry.toAbsolutePath().toString());
        return dir;
    }

    /**
     * The Java level the classes were compiled for (class file major version minus 44), looking at up to
     * 300 classes; 8 at least, and 8 when nothing can be read. Versioned entries of multi-release jars
     * and module descriptors do not count.
     */
    public static int javaLevel(List<Path> classRoots) {
        int major = 0, seen = 0;
        for (Path root : classRoots) {
            try {
                if (Files.isDirectory(root)) {
                    try (Stream<Path> s = Files.walk(root)) {
                        for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class") && !x.getFileName().toString().equals("module-info.class")).limit(300)::iterator) {
                            try (InputStream in = Files.newInputStream(p)) { major = Math.max(major, majorOf(in)); }
                            seen++;
                        }
                    }
                } else if (Files.isRegularFile(root)) {
                    try (ZipFile zip = new ZipFile(root.toFile())) {
                        Enumeration<? extends ZipEntry> en = zip.entries();
                        while (en.hasMoreElements() && seen < 300) {
                            ZipEntry e = en.nextElement();
                            String n = e.getName();
                            if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) continue;
                            try (InputStream in = zip.getInputStream(e)) { major = Math.max(major, majorOf(in)); }
                            seen++;
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable root: the default applies
            }
        }
        return major == 0 ? 8 : Math.max(8, major - 44);
    }

    private static int majorOf(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        if (d.readInt() != 0xCAFEBABE) return 0;
        d.readUnsignedShort();
        return d.readUnsignedShort();
    }
}
