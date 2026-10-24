package cn.ac.ios.justin.samples;

/**
 * String-manipulating code whose branches need structured inputs: the cases of the regex-guided
 * method (API chains over String, StringBuilder and StringBuffer, nested predicates, indices that
 * are parameters, inputs that make an API throw).
 */
public final class TextRules {
    private TextRules() {}

    /** The motivating example: the third '#'-separated field must carry "class" at positions 3..8. */
    public static String field(String str) {
        String[] parts = str.split("#");
        String third = parts[2];
        String sub = third.substring(3, 8);
        if (sub.equals("class")) {
            return "class-field";
        }
        return "other:" + sub;
    }

    /** Nested predicates on one parameter. */
    public static int route(String path) {
        if (path.startsWith("/api/")) {
            if (path.endsWith(".json")) {
                return path.contains("admin") ? 3 : 2;
            }
            return 1;
        }
        return 0;
    }

    /** Case conversion and trimming before the comparison. */
    public static boolean isYes(String answer) {
        String norm = answer.trim().toLowerCase();
        if (norm.equals("yes")) return true;
        if (norm.length() > 3 && norm.charAt(0) == 'y') return norm.indexOf('!') >= 0;
        return false;
    }

    /** A builder assembled from the parameter, observed after the appends. */
    public static String tag(String name) {
        StringBuilder sb = new StringBuilder("<");
        sb.append(name);
        sb.append(">");
        String s = sb.toString();
        if (s.equals("<body>")) return "root";
        if (s.length() > 12) return "long";
        return s;
    }

    /** A StringBuffer parameter that is extended and then inspected. */
    public static int suffix(StringBuffer buf) {
        buf.append(".log");
        if (buf.toString().endsWith("error.log")) return 2;
        if (buf.indexOf("warn") >= 0) return 1;
        return 0;
    }

    /** The index is a parameter as well. */
    public static char pick(String s, int i) {
        if (s.length() > i && Character.isDigit(s.charAt(i))) return 'd';
        if (s.substring(i).startsWith("ab")) return 'a';
        return '-';
    }

    /** Two string parameters in one predicate, and a numeral. */
    public static int compare(String key, String prefix) {
        if (key.startsWith(prefix) && prefix.length() >= 2) {
            return Integer.parseInt(key.substring(prefix.length())) > 40 ? 2 : 1;
        }
        return 0;
    }

    /** The positive outcome needs a pass through the loop body. */
    public static boolean allDigits(CharSequence cs) {
        if (cs == null || cs.length() == 0) return false;
        for (int i = 0; i < cs.length(); i++) {
            if (!Character.isDigit(cs.charAt(i))) return false;
        }
        return true;
    }

    /** The last characters decide. */
    public static int closing(String s) {
        if (s.length() > 2 && s.charAt(s.length() - 1) == ';') {
            return Character.isDigit(s.charAt(s.length() - 2)) ? 2 : 1;
        }
        return 0;
    }

    /** A public facade over a worker: the interesting lines are the worker's. */
    public static int cut(String s, String sep) {
        return cutWorker(s, sep, true);
    }

    private static int cutWorker(String s, String sep, boolean keep) {
        if (s == null) {
            return -1;
        }
        if (sep == null || sep.isEmpty()) {
            return 0;
        }
        if (keep && s.endsWith(sep)) {
            return 100 + s.length();
        }
        return s.contains(sep) ? 2 : 1;
    }

    /** Several segments of one split constrained together, under a guard on their number (Listing 1 of the split paper). */
    public static int authenticate(String input) {
        String[] parts = input.split("#", 3);
        if (parts.length >= 3) {
            String username = parts[0];
            String password = parts[1];
            if (username.equals("admin") && password.length() > 8) {
                return 2;
            } else if (parts[2].contains("token")) {
                return 1;
            }
        }
        return 0;
    }

    /** The key=value idiom: the index is itself derived from the string. */
    public static String valueOf(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) return null;
        String key = line.substring(0, eq);
        String value = line.substring(eq + 1);
        if (key.equals("mode") && value.startsWith("fast")) return "fast-mode";
        return value.isEmpty() ? "empty" : value;
    }

    /** A regular expression as delimiter, and a split of a split. */
    public static String header(String line) {
        String[] kv = line.split("\\s*:\\s*");
        if (kv.length == 2 && kv[0].equals("Accept")) {
            String[] types = kv[1].split(",");
            if (types.length > 1 && types[1].startsWith("text/")) return "second-text";
            return "accept";
        }
        return "other";
    }

    /** The extension idiom: both sides of the last occurrence. */
    public static String extension(String file) {
        int dot = file.lastIndexOf('.');
        if (dot <= 0) return "";
        String ext = file.substring(dot + 1);
        if (ext.equals("gz") && file.substring(0, dot).endsWith(".tar")) return "tar.gz";
        return ext.isEmpty() ? "none" : ext;
    }

    /** Two segments of one split related to each other (after KeyMap.range of jline). */
    public static int span(String range) {
        String[] keys = range.split("-");
        if (keys.length != 2) return -1;
        if (keys[0].length() != keys[1].length()) return -2;
        char lo = keys[0].charAt(keys[0].length() - 1);
        char hi = keys[1].charAt(keys[1].length() - 1);
        if (lo > hi) return -3;
        return hi - lo;
    }

    static boolean blank(CharSequence cs) {
        return cs == null || cs.length() == 0;
    }

    static int size(CharSequence cs) {
        return cs == null ? 0 : cs.length();
    }

    /** Predicates hidden in project helpers, the way StringUtils.isEmpty(cs) hides them. */
    public static String label(String s) {
        if (blank(s)) return "none";
        if (size(s) > 6 && s.startsWith("#")) return "long-tag";
        return s.startsWith("#") ? "tag" : "text";
    }

    public static String kind(String code) {
        switch (code) {
            case "alpha": return "A";
            case "beta": return "B";
            default: return code.matches("[0-9]{3}-[a-z]+") ? "serial" : "?";
        }
    }
}
