package cn.ac.ios.justin.llm;

import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finds the source of a method when source roots are available: the whole file if small, else a window around the method. */
public final class SourceSnippets {
    private SourceSnippets() {}

    /**
     * The focal method's snippet followed by the snippets of the same-class methods it appears to call
     * (JNN's dependency-graph context, depth 1, at most three callees), so contracts enforced in helpers
     * are visible to the model.
     */
    public static String withCallees(List<Path> sourceRoots, ClassTarget owner, MethodTarget m) {
        String focal = forMethod(sourceRoots, owner, m);
        if (focal == null) return null;
        StringBuilder sb = new StringBuilder(focal);
        int added = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MethodTarget other : owner.methods()) {
            if (added >= 3) break;
            if (other.equals(m) || other.isConstructor() || other.isSynthetic() || !seen.add(other.name())) continue;
            if (!focal.contains(other.name() + "(")) continue;
            String snippet = forMethod(sourceRoots, owner, other);
            if (snippet == null || snippet.equals(focal)) continue;
            if (snippet.length() > 2000) snippet = snippet.substring(0, 2000) + "\n...";
            sb.append("\n// --- called helper: ").append(other.name()).append(" ---\n").append(snippet);
            added++;
        }
        return sb.toString();
    }

    public static String forMethod(List<Path> sourceRoots, ClassTarget owner, MethodTarget m) {
        if (sourceRoots == null || sourceRoots.isEmpty()) return null;
        String outer = owner.binaryName();
        int dollar = outer.indexOf('$');
        if (dollar > 0) outer = outer.substring(0, dollar);
        String rel = outer.replace('.', '/') + ".java";
        for (Path root : sourceRoots) {
            Path f = root.resolve(rel);
            if (!Files.exists(f)) continue;
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (lines.size() <= 200) return String.join("\n", lines);
                int line = m.firstLine();
                if (line <= 0) return null;
                // the signature and Javadoc sit above the first executable line; the body below
                int from = Math.max(0, line - 25), to = Math.min(lines.size(), line + 45);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < to; i++) sb.append(lines.get(i)).append('\n');
                return sb.toString();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
}
