package cn.ac.ios.justin.api;

import java.nio.file.Path;
import java.util.List;

/** Explicit paths: the simplest model, used by the CLI and by tests. */
public record ClasspathProjectModel(String name, List<Path> targetRoots, List<Path> dependencyClasspath, List<Path> sourceRoots, int javaVersion) implements ProjectModel {
    public ClasspathProjectModel {
        targetRoots = List.copyOf(targetRoots);
        dependencyClasspath = List.copyOf(dependencyClasspath);
        sourceRoots = List.copyOf(sourceRoots);
    }
}
