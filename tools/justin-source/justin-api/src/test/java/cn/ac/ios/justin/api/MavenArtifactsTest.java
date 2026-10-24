package cn.ac.ios.justin.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The forms a Maven subject can be given in, without touching the network. */
class MavenArtifactsTest {

    private static void check(String link, String g, String a, String v, String repo) {
        MavenArtifacts.Coordinates c = MavenArtifacts.parse(link);
        assertEquals(g, c.groupId(), link);
        assertEquals(a, c.artifactId(), link);
        assertEquals(v, c.version(), link);
        assertEquals(repo, c.repository(), link);
    }

    @Test
    void coordinates() {
        check("org.apache.commons:commons-lang3:3.17.0", "org.apache.commons", "commons-lang3", "3.17.0", null);
        check("org.apache.commons:commons-lang3", "org.apache.commons", "commons-lang3", null, null);
        check("org.apache.commons:commons-lang3:jar:3.17.0", "org.apache.commons", "commons-lang3", "3.17.0", null);
        assertThrows(IllegalArgumentException.class, () -> MavenArtifacts.parse("commons-lang3"));
    }

    @Test
    void repositoryLinks() {
        String central = "https://repo1.maven.org/maven2";
        check(central + "/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar", "org.apache.commons", "commons-lang3", "3.17.0", central);
        check(central + "/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.pom", "org.apache.commons", "commons-lang3", "3.17.0", central);
        check(central + "/org/apache/commons/commons-lang3/3.17.0/", "org.apache.commons", "commons-lang3", "3.17.0", central);
        check(central + "/org/apache/commons/commons-lang3/", "org.apache.commons", "commons-lang3", null, central);
        check(central + "/org/apache/commons/commons-lang3/maven-metadata.xml", "org.apache.commons", "commons-lang3", null, central);
        check("https://maven.aliyun.com/repository/public/org/jsoup/jsoup/1.17.2/jsoup-1.17.2.jar", "org.jsoup", "jsoup", "1.17.2", "https://maven.aliyun.com/repository/public");
        check("https://nexus.example.org/nexus/content/groups/public/com/acme/tool/2.0/tool-2.0.jar", "com.acme", "tool", "2.0", "https://nexus.example.org/nexus/content/groups/public");
        check("https://repo.example.org/artifactory/libs-release/com/acme/tool/2.0/tool-2.0-sources.jar", "com.acme", "tool", "2.0", "https://repo.example.org/artifactory/libs-release");
    }

    @Test
    void artifactPages() {
        check("https://mvnrepository.com/artifact/org.apache.commons/commons-lang3/3.17.0", "org.apache.commons", "commons-lang3", "3.17.0", null);
        check("https://mvnrepository.com/artifact/org.apache.commons/commons-lang3", "org.apache.commons", "commons-lang3", null, null);
        check("https://central.sonatype.com/artifact/org.apache.commons/commons-lang3/3.17.0", "org.apache.commons", "commons-lang3", "3.17.0", null);
        check("https://search.maven.org/artifact/org.apache.commons/commons-lang3/3.17.0/jar", "org.apache.commons", "commons-lang3", "3.17.0", null);
    }

    @Test
    void sourcesJarIsUnpackedOnceAndJavaLevelIsReadOffTheClasses() throws Exception {
        Path dir = Files.createTempDirectory("justin-inputs");
        Path jar = dir.resolve("demo-sources.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("demo/A.java"));
            z.write("package demo; class A {}".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("../escape.txt"));
            z.write(1);
            z.closeEntry();
        }
        Path root = SubjectInputs.sourceRoot(jar);
        assertTrue(Files.isRegularFile(root.resolve("demo/A.java")));
        assertFalse(Files.exists(root.getParent().resolve("escape.txt")));
        assertEquals(root, SubjectInputs.sourceRoot(jar));
        assertEquals(dir, SubjectInputs.sourceRoot(dir));
        // this test class itself was compiled for the project's level
        Path classes = Path.of(MavenArtifactsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(17, SubjectInputs.javaLevel(List.of(classes)));
        assertEquals(8, SubjectInputs.javaLevel(List.of(dir)));
    }
}
