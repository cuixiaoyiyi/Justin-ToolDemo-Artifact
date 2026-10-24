package cn.ac.ios.justin.strategies;

import com.github.curiousoddman.rgxgen.RgxGen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;

/** Strings sampled from (and outside) the language of a regular expression, via RgxGen; deterministic under a seeded Random. */
public final class RegexStrings {
    private RegexStrings() {}

    private static final Map<String, RgxGen> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_LENGTH = 200;

    private static RgxGen parse(String regex) {
        return CACHE.computeIfAbsent(regex, r -> {
            try {
                return RgxGen.parse(r);
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    /** True when the regex is one java.util.regex accepts; unparsable patterns yield no samples. */
    public static boolean valid(String regex) {
        try {
            java.util.regex.Pattern.compile(regex);
            return parse(regex) != null;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    public static boolean matches(String regex, String s) {
        try {
            return java.util.regex.Pattern.matches(regex, s);
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Up to n distinct strings matching the regex (verified with java.util.regex), shortest first. */
    public static List<String> matching(String regex, int n, Random rnd) {
        RgxGen g = parse(regex);
        if (g == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < n * 4 && out.size() < n; i++) {
            String s;
            try {
                s = g.generate(rnd);
            } catch (RuntimeException e) {
                break;
            }
            if (s == null || s.length() > MAX_LENGTH || !matches(regex, s)) continue;
            out.add(s);
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort((a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b));
        return sorted;
    }

    /** Up to n distinct strings that do not match the regex (verified), plus nothing if the generator cannot find one. */
    public static List<String> notMatching(String regex, int n, Random rnd) {
        RgxGen g = parse(regex);
        Set<String> out = new LinkedHashSet<>();
        if (g != null) {
            for (int i = 0; i < n * 4 && out.size() < n; i++) {
                String s;
                try {
                    s = g.generateNotMatching(rnd);
                } catch (RuntimeException e) {
                    break;
                }
                if (s == null || s.length() > MAX_LENGTH || matches(regex, s)) continue;
                out.add(s);
            }
        }
        // cheap universal candidates: a mutated match and the empty string
        for (String m : matching(regex, 1, rnd)) {
            String mutated = "#" + m + "#";
            if (!matches(regex, mutated)) out.add(mutated);
        }
        if (!matches(regex, "")) out.add("");
        return new ArrayList<>(out).subList(0, Math.min(n, out.size()));
    }
}
