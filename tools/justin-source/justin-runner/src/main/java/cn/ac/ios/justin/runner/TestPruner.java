package cn.ac.ios.justin.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes individual test methods that javac rejected, so the remaining tests of the file survive.
 * Relies on the emitter's fixed layout: each test is "javadoc, @Test, @Timeout, signature, body, '    }'"
 * at one indentation level inside the class.
 */
public final class TestPruner {

    private static final Pattern LINE_REF = Pattern.compile("^line (\\d+):");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?[\\w.]*\\.(\\w+);\\s*$");

    public record Result(int removedMethods, List<String> removedNames, boolean fileDeleted) {}

    private TestPruner() {}

    public static List<Integer> errorLines(List<String> diagnostics) {
        List<Integer> out = new ArrayList<>();
        for (String d : diagnostics) {
            Matcher m = LINE_REF.matcher(d);
            if (m.find()) out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    /** Removes the named test methods (by exact method name) from the file. */
    public static Result pruneByName(Path file, java.util.Collection<String> names) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = Pattern.compile("public void (\\w+)\\(\\)").matcher(lines.get(i));
            if (m.find() && names.contains(m.group(1))) hits.add(i + 1);
        }
        if (hits.isEmpty()) return new Result(0, List.of(), false);
        return prune(file, hits);
    }

    /** @return number of methods removed; 0 means the errors were outside any test method (nothing we can do) */
    public static Result prune(Path file, List<Integer> errorLines) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        TreeSet<int[]> ranges = new TreeSet<>((a, b) -> Integer.compare(a[0], b[0]));
        List<String> names = new ArrayList<>();
        List<Integer> badImports = new ArrayList<>();
        for (int ln : errorLines) {
            int idx = ln - 1;
            if (idx < 0 || idx >= lines.size()) continue;
            Matcher imp = IMPORT.matcher(lines.get(idx));
            if (imp.matches()) {
                // an unresolvable import (e.g. an exception class newer than the target release):
                // remove the import and every test that mentions the imported simple name
                badImports.add(idx);
                String simple = imp.group(1);
                Pattern use = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b");
                for (int i = 0; i < lines.size(); i++) {
                    if (i == idx || !use.matcher(lines.get(i)).find()) continue;
                    int s = findMethodStart(lines, i), e = findMethodEnd(lines, i);
                    if (s >= 0 && e >= 0) ranges.add(new int[]{s, e});
                }
                continue;
            }
            int start = findMethodStart(lines, idx);
            int end = findMethodEnd(lines, idx);
            if (start < 0 || end < 0) continue;
            ranges.add(new int[]{start, end});
        }
        for (int i = badImports.size() - 1; i >= 0; i--) {
            int idx = badImports.get(i);
            // import lines precede every method, so removing them does not shift method ranges below... but it does:
            // handle by turning the line into a comment instead of deleting it
            lines.set(idx, "// removed: " + lines.get(idx));
        }
        if (ranges.isEmpty()) return new Result(0, List.of(), false);
        // merge overlapping ranges and delete from the bottom up
        List<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
            } else merged.add(r);
        }
        int removed = 0;
        for (int i = merged.size() - 1; i >= 0; i--) {
            int[] r = merged.get(i);
            for (int k = r[0]; k <= r[1]; k++) {
                Matcher m = Pattern.compile("public void (\\w+)\\(\\)").matcher(lines.get(k));
                if (m.find()) { names.add(m.group(1)); removed++; }
            }
            lines.subList(r[0], r[1] + 1).clear();
            // drop a blank line left behind
            if (r[0] < lines.size() && r[0] > 0 && lines.get(r[0]).isBlank() && lines.get(r[0] - 1).isBlank()) lines.remove(r[0]);
        }
        boolean anyTestLeft = lines.stream().anyMatch(l -> l.contains("@Test"));
        if (!anyTestLeft) {
            Files.delete(file);
            return new Result(removed, names, true);
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return new Result(removed, names, false);
    }

    /** Walks up to the javadoc opening of the enclosing test method (the line "    /**"). */
    private static int findMethodStart(List<String> lines, int idx) {
        int i = idx;
        while (i >= 0) {
            String l = lines.get(i);
            if (l.equals("    /**")) return i;
            if (l.startsWith("public class") || l.startsWith("import ") || l.startsWith("package ")) return -1;
            i--;
        }
        return -1;
    }

    /** Walks down to the method's closing brace (the line "    }"). */
    private static int findMethodEnd(List<String> lines, int idx) {
        for (int i = idx; i < lines.size(); i++) {
            if (lines.get(i).equals("    }")) return i;
            if (lines.get(i).equals("}")) return -1;
        }
        return -1;
    }
}
