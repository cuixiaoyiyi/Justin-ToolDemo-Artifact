package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.strategies.RegexStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

/**
 * A regular expression kept as a sequence of segments, so that the composition steps of the
 * regex-guided method (replace or bound the open segment, concatenate, restrict to a delimiter-free
 * class) are structural instead of edits of a pattern string. {@link #toRegex()} gives the pattern
 * with its exact meaning; {@link #sample} draws a member string directly from the segments and uses
 * RgxGen only for opaque fragments.
 */
public final class Rx {

    public sealed interface Seg permits Lit, Open, Raw {}

    /** A literal piece. */
    public record Lit(String text) implements Seg {}

    /** {@code cls{min,max}}; max = -1 is unbounded (the paper's open segment when cls is ANY). */
    public record Open(int min, int max, CharCls cls) implements Seg {
        boolean flexible() { return max == -1 || max > min; }
    }

    /** An opaque regex fragment with a known minimum length: split prefixes, user-supplied patterns. */
    public record Raw(String regex, int minLen) implements Seg {}

    /** A one-character class with the alphabet samples are drawn from. */
    public record CharCls(String regex, String alphabet) {
        static final String BASE = "abcxyzmk0189ABZ";
        public static final CharCls ANY = new CharCls("[\\s\\S]", BASE);
        public static final CharCls DIGIT = new CharCls("[0-9]", "0123456789");
        public static final CharCls LETTER = new CharCls("[a-zA-Z]", "abcxyzABZ");
        public static final CharCls ALNUM = new CharCls("[a-zA-Z0-9]", "abcxyz019ABZ");
        public static final CharCls UPPER = new CharCls("[A-Z]", "ABCXYZ");
        public static final CharCls LOWER = new CharCls("[a-z]", "abcxyz");
        public static final CharCls SPACE = new CharCls("[ \\t]", " ");
        public static final CharCls NON_SPACE = new CharCls("[^\\s]", BASE);
        public static final CharCls NON_ALNUM = new CharCls("[^a-zA-Z0-9]", "-_#.");
        public static final CharCls NON_LETTER = new CharCls("[^a-zA-Z]", "0189-_");
        public static final CharCls NON_DIGIT = new CharCls("[^0-9]", "abcxyzABZ");

        /** Any character except the given ones. */
        public static CharCls not(String chars) {
            StringBuilder re = new StringBuilder("[^");
            for (char c : chars.toCharArray()) re.append(quote(c));
            re.append(']');
            StringBuilder alpha = new StringBuilder();
            for (char c : (BASE + "-_").toCharArray()) if (chars.indexOf(c) < 0) alpha.append(c);
            return new CharCls(re.toString(), alpha.length() == 0 ? "~" : alpha.toString());
        }

        /** This class without the given characters (a delimiter that must not occur inside a split piece). */
        CharCls minus(String chars) {
            if (this == ANY || regex.equals(ANY.regex)) return not(chars);
            StringBuilder alpha = new StringBuilder();
            for (char c : alphabet.toCharArray()) if (chars.indexOf(c) < 0) alpha.append(c);
            if (alpha.length() == alphabet.length()) return this;
            // the pattern keeps the wider class; only samples have to avoid the characters
            return new CharCls(regex, alpha.length() == 0 ? alphabet : alpha.toString());
        }
    }

    private final List<Seg> segs;

    private Rx(List<Seg> segs) { this.segs = List.copyOf(normalize(segs)); }

    public static Rx of(Seg... segs) { return new Rx(List.of(segs)); }
    public static Rx of(List<Seg> segs) { return new Rx(segs); }
    public static Rx lit(String s) { return of(new Lit(s)); }
    /** {@code [\s\S]*} */
    public static Rx any() { return of(new Open(0, -1, CharCls.ANY)); }
    public static Rx len(int min, int max) { return of(new Open(min, max, CharCls.ANY)); }
    public static Rx raw(String regex, int minLen) { return of(new Raw(regex, minLen)); }

    public List<Seg> segs() { return segs; }

    private static List<Seg> normalize(List<Seg> in) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : in) {
            if (s instanceof Lit l && l.text().isEmpty()) continue;
            if (s instanceof Open o && o.max() == 0) continue;
            if (s instanceof Lit l && !out.isEmpty() && out.get(out.size() - 1) instanceof Lit p) {
                out.set(out.size() - 1, new Lit(p.text() + l.text()));
                continue;
            }
            if (s instanceof Open o && !out.isEmpty() && out.get(out.size() - 1) instanceof Open p && p.cls().equals(o.cls())) {
                int max = p.max() == -1 || o.max() == -1 ? -1 : p.max() + o.max();
                out.set(out.size() - 1, new Open(p.min() + o.min(), max, o.cls()));
                continue;
            }
            out.add(s);
        }
        return out;
    }

    public Rx concat(Rx other) {
        List<Seg> all = new ArrayList<>(segs);
        all.addAll(other.segs);
        return new Rx(all);
    }

    public int minLength() {
        int n = 0;
        for (Seg s : segs) n += s instanceof Lit l ? l.text().length() : s instanceof Open o ? o.min() : ((Raw) s).minLen();
        return n;
    }

    /** The literal this expression denotes when it has no other segment, else null. */
    public String asLiteral() {
        if (segs.isEmpty()) return "";
        return segs.size() == 1 && segs.get(0) instanceof Lit l ? l.text() : null;
    }

    /**
     * BoundOpenSegment of the paper's Merge: the same expression restricted to strings of exactly
     * {@code total} characters, or null when it has none. The spare length goes to the last flexible
     * segment first; this keeps literals where the expression put them.
     */
    public Rx bound(int total) {
        int extra = total - minLength();
        if (extra < 0) return null;
        List<Seg> out = new ArrayList<>(segs);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (!(out.get(i) instanceof Open o)) continue;
            int room = o.max() == -1 ? extra : Math.min(extra, o.max() - o.min());
            out.set(i, new Open(o.min() + room, o.min() + room, o.cls()));
            extra -= room;
        }
        if (extra > 0) return null;
        List<Seg> kept = new ArrayList<>();
        for (Seg s : out) if (!(s instanceof Open o) || o.max() != 0) kept.add(s);
        return new Rx(kept);
    }

    /** Open segments may no longer produce the given characters (the pieces of a split never contain the delimiter). */
    public Rx without(String chars) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : segs) out.add(s instanceof Open o ? new Open(o.min(), o.max(), o.cls().minus(chars)) : s);
        return new Rx(out);
    }

    public Rx mapLiterals(UnaryOperator<String> f) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : segs) out.add(s instanceof Lit l ? new Lit(f.apply(l.text())) : s);
        return new Rx(out);
    }

    public boolean literalsAll(java.util.function.Predicate<String> p) {
        for (Seg s : segs) if (s instanceof Lit l && !p.test(l.text())) return false;
        return true;
    }

    /**
     * Left quotient by a literal: an expression for the strings x with {@code prefix + x} in this
     * language (one solution when several exist), or null when there is none of the shapes handled.
     */
    public Rx stripPrefix(String prefix) {
        List<Seg> rest = new ArrayList<>(segs);
        String p = prefix;
        while (!p.isEmpty()) {
            if (rest.isEmpty()) return null;
            Seg s = rest.get(0);
            if (s instanceof Lit l) {
                if (l.text().startsWith(p)) { rest.set(0, new Lit(l.text().substring(p.length()))); p = ""; }
                else if (p.startsWith(l.text())) { rest.remove(0); p = p.substring(l.text().length()); }
                else return null;
            } else if (s instanceof Open o) {
                // the open segment absorbs the rest of the literal
                int take = o.max() == -1 ? p.length() : Math.min(p.length(), o.max());
                if (take < p.length()) { rest.remove(0); p = p.substring(take); continue; }
                rest.set(0, new Open(Math.max(0, o.min() - take), o.max() == -1 ? -1 : o.max() - take, o.cls()));
                p = "";
            } else {
                return null;
            }
        }
        return new Rx(rest);
    }

    /** Right quotient by a literal, the mirror image of {@link #stripPrefix}. */
    public Rx stripSuffix(String suffix) {
        List<Seg> rest = new ArrayList<>(segs);
        String p = suffix;
        while (!p.isEmpty()) {
            if (rest.isEmpty()) return null;
            int last = rest.size() - 1;
            Seg s = rest.get(last);
            if (s instanceof Lit l) {
                if (l.text().endsWith(p)) { rest.set(last, new Lit(l.text().substring(0, l.text().length() - p.length()))); p = ""; }
                else if (p.endsWith(l.text())) { rest.remove(last); p = p.substring(0, p.length() - l.text().length()); }
                else return null;
            } else if (s instanceof Open o) {
                int take = o.max() == -1 ? p.length() : Math.min(p.length(), o.max());
                if (take < p.length()) { rest.remove(last); p = p.substring(0, p.length() - take); continue; }
                rest.set(last, new Open(Math.max(0, o.min() - take), o.max() == -1 ? -1 : o.max() - take, o.cls()));
                p = "";
            } else {
                return null;
            }
        }
        return new Rx(rest);
    }

    /** The pattern, with the meaning the segments have. */
    public String toRegex() {
        StringBuilder sb = new StringBuilder();
        for (Seg s : segs) {
            if (s instanceof Lit l) { for (char c : l.text().toCharArray()) sb.append(quote(c)); }
            else if (s instanceof Open o) sb.append(o.cls().regex()).append(quantifier(o.min(), o.max()));
            else sb.append("(?:").append(((Raw) s).regex()).append(')');
        }
        return sb.toString();
    }

    private static String quantifier(int min, int max) {
        if (max == -1) return min == 0 ? "*" : min == 1 ? "+" : "{" + min + ",}";
        if (min == max) return min == 1 ? "" : "{" + min + "}";
        return "{" + min + "," + max + "}";
    }

    static String quote(char c) {
        if (Character.isLetterOrDigit(c) && c < 128) return String.valueOf(c);
        if (c == ' ' || c == '_' || c == ',' || c == ':' || c == ';' || c == '@' || c == '%' || c == '~' || c == '=' || c == '<' || c == '>' || c == '!' || c == '"' || c == '\'') return String.valueOf(c);
        if (c < 128 && c >= 32) return "\\" + c;
        return String.format("\\u%04x", (int) c);
    }

    /**
     * A member of the language. The first draw (slack 0) is the shortest shape; later draws lengthen
     * flexible segments by up to {@code slack} characters.
     */
    public String sample(Random rnd, int slack) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : segs) {
            if (s instanceof Lit l) sb.append(l.text());
            else if (s instanceof Open o) {
                int room = o.max() == -1 ? slack : Math.min(slack, o.max() - o.min());
                int n = o.min() + (room > 0 ? rnd.nextInt(room + 1) : 0);
                String alpha = o.cls().alphabet();
                for (int i = 0; i < n; i++) sb.append(alpha.charAt(slack == 0 ? i % alpha.length() : rnd.nextInt(alpha.length())));
            } else {
                List<String> m = RegexStrings.matching(((Raw) s).regex(), slack == 0 ? 1 : 3, rnd);
                if (m.isEmpty()) return null;
                sb.append(m.get(slack == 0 ? 0 : rnd.nextInt(m.size())));
            }
        }
        return sb.toString();
    }

    @Override public String toString() { return toRegex(); }
    @Override public boolean equals(Object o) { return o instanceof Rx r && r.segs.equals(segs); }
    @Override public int hashCode() { return segs.hashCode(); }
}
