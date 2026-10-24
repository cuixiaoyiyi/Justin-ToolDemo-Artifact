package cn.ac.ios.justin.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the subject's classes come from. Front-ends implement this from their own project knowledge
 * (Maven, Gradle, an IDE's module model); the engine never resolves dependencies itself.
 */
public interface ProjectModel {
    /** Display name (module or jar name). */
    String name();

    /** Directories or jars holding the classes to generate tests for. */
    List<Path> targetRoots();

    /** Everything else needed to load and run the target classes (dependency jars, sibling modules). */
    List<Path> dependencyClasspath();

    /** Optional source roots (for parameter names in a later phase and for HTML coverage). */
    List<Path> sourceRoots();

    /** Java language level the subject was compiled for; generated tests must not exceed it. */
    int javaVersion();

    /** The buildable modules of a multi-module project, in dependency order; a single-module project is its own only module. */
    default List<ProjectModel> modules() { return List.of(this); }
    /** The module of that name (artifactId or directory name), restricted to its own classes and classpath. */
    default java.util.Optional<ProjectModel> module(String name) {
        return modules().stream().filter(m -> m != this && m.name().equals(name)).findFirst();
    }
    default List<Path> fullClasspath() {
        List<Path> all = new java.util.ArrayList<>(targetRoots());
        all.addAll(dependencyClasspath());
        return all;
    }
}
