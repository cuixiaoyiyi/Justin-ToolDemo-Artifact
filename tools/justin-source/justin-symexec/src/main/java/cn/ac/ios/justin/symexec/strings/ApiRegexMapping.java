package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.strategies.RegexStrings;
import cn.ac.ios.justin.symexec.strings.Rx.CharCls;
import cn.ac.ios.justin.symexec.strings.Rx.Lit;
import cn.ac.ios.justin.symexec.strings.Rx.Open;
import cn.ac.ios.justin.symexec.strings.Rx.Seg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The API-Regex mapping and the backward inference over it (Section 3.4 and Algorithm 4 of the
 * regex-guided method). A predicate on the path ends in a <em>terminated</em> API (equals, startsWith,
 * length, charAt, indexOf, ...) whose outcome fixes a regex for the API's caller; the caller is
 * usually not the parameter itself but a value derived from it through <em>non-terminated</em> APIs
 * (substring, split(d)[j], trim, case conversion, builder appends), and each of those lifts the regex
 * one step towards the parameter with {@link RegexWrapper#merge}.
 *
 * <p>Argument values come from evaluating their terms under the values already chosen for other
 * parameters, so an index or delimiter that is itself a parameter is a literal by the time it is
 * needed (the paper materializes literals and keeps placeholders otherwise).
 */
public final class ApiRegexMapping {
    private ApiRegexMapping() {}

    /** What a predicate demands of one parameter. */
    public sealed interface Inferred permits Pattern, IsNull, NotNull, Demands {}

    /**
     * A delimiter-induced structure of a value: the pieces of {@code base.split(delim, limit)}, or the
     * two sides of the first (FIRST) or last (LAST) occurrence of delim in base, as indexOf and lastIndexOf
     * with substring carve them out. {@code exclude} lists the characters a delimiter-free piece avoids
     * (for a regex delimiter, delim is one instance of it and exclude covers the characters it can match).
     */
    public record Partition(Term base, String delim, String exclude, Mode mode, int limit) {
        public enum Mode { SPLIT, FIRST, LAST }
    }

    /**
     * What a predicate asks of a partition: segment {@code index} matches rx (AT), the remainder from
     * segment {@code index} matches rx (REST; for LAST the part before the last occurrence), or the number
     * of segments satisfies {@code op n} (COUNT). Demands are collected per partition and answered
     * together by {@link SegmentProfile}.
     */
    public record Demand(Partition part, Access access, int index, Rx rx, String op, long n, Kind kind) implements Lifted {
        public enum Access { AT, REST, COUNT }
        Demand withKind(Kind k) { return new Demand(part, access, index, rx, op, n, k); }
    }

    public record Demands(List<Demand> list) implements Inferred {}

    /** Where lifting a regex towards the parameter ended: at the parameter, or at a partition of a value derived from it. */
    public sealed interface Lifted permits ParamRx, Demand {}
    public record ParamRx(Rx rx) implements Lifted {}
    public record Pattern(Rx rx, Kind kind) implements Inferred {}

    /**
     * How a regex relates to the predicate it came from. NECESSARY: every satisfying string matches it.
     * CHOICE: one family of satisfying strings among several (an index picked for indexOf, a case picked
     * under toLowerCase, a numeral picked for parseInt); it can be combined with the others. AVOID: a
     * witness for a negative outcome (some string that is not equal to s); only a candidate of its own.
     */
    public enum Kind { NECESSARY, CHOICE, AVOID }
    public record IsNull() implements Inferred {}
    public record NotNull() implements Inferred {}

    /** The demand the predicate (with its outcome on the path) puts on parameter p, or null when the mapping has no entry for its shape. */
    public static Inferred infer(Term.Pred pred, int p, Map<Integer, Object> env) {
        try {
            Set<Integer> lp = new HashSet<>(), rp = new HashSet<>();
            Term.params(pred.left(), lp);
            Term.params(pred.right(), rp);
            boolean inL = lp.contains(p), inR = rp.contains(p);
            if (inL == inR) return null;
            Term t = inL ? pred.left() : pred.right();
            String op = inL ? pred.effectiveOp() : Term.Pred.flip(pred.effectiveOp());
            Object k = Term.eval(inL ? pred.right() : pred.left(), env);
            return relation(t, op, k, p, env);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The exception-triggering variant of a fault site: a regex for p under which the caller of the
     * API is too short for its index arguments (or has too few pieces for the selected one).
     */
    public static Rx faultVariant(Term site, int p, Map<Integer, Object> env) {
        try {
            if (site instanceof Term.Call c && c.base() != null) {
                int need;
                if (c.name().equals("charAt")) need = (int) Term.number(Term.eval(c.args().get(0), env)) + 1;
                else if (c.args().size() == 1) need = (int) Term.number(Term.eval(c.args().get(0), env));
                else need = (int) Math.max(Term.number(Term.eval(c.args().get(0), env)), Term.number(Term.eval(c.args().get(1), env)));
                if (need <= 0) return null;
                return rxOf(pushDown(c.base(), Rx.len(0, need - 1), p, env));
            }
            if (site instanceof Term.Elem e && e.array() instanceof Term.Call split && split.name().equals("split")) {
                int j = (int) Term.number(Term.eval(e.index(), env));
                String d = delimiter(Term.eval(split.args().get(0), env));
                if (j < 1 || d == null) return null;
                List<Seg> segs = new ArrayList<>();
                for (int i = 0; i < j - 1; i++) { segs.add(new Open(1, 2, CharCls.not(d))); segs.add(new Lit(d)); }
                segs.add(new Open(1, 2, CharCls.not(d)));
                return rxOf(pushDown(split.base(), Rx.of(segs), p, env));
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ terminated APIs

    private static Inferred relation(Term t, String op, Object k, int p, Map<Integer, Object> env) {
        if (t instanceof Term.Param) {
            if (k != null) return null;
            return op.equals("==") ? new IsNull() : op.equals("!=") ? new NotNull() : null;
        }
        if (k == null) {
            // a derived value compared with null says the parameter it derives from is a usable string at least
            return null;
        }
        if (t instanceof Term.Bin b) {
            long kk = Term.number(k);
            Set<Integer> lp = new HashSet<>();
            Term.params(b.left(), lp);
            if (lp.contains(p)) {
                long c = Term.number(Term.eval(b.right(), env));
                return relation(b.left(), op, (int) (b.op().equals("+") ? kk - c : kk + c), p, env);
            }
            long c = Term.number(Term.eval(b.left(), env));
            if (b.op().equals("+")) return relation(b.right(), op, (int) (kk - c), p, env);
            return relation(b.right(), Term.Pred.flip(op), (int) (c - kk), p, env);
        }
        if (t instanceof Term.Len len && len.array() instanceof Term.Call split && split.name().equals("split") && split.base() != null) {
            return pieces(split, op, Term.number(k), p, env);
        }
        if (!(t instanceof Term.Call c)) return null;
        if ((c.name().equals("indexOf") || c.name().equals("lastIndexOf")) && c.base() != null && c.args().size() == 1 && Term.isStringLike(c.owner())
                && mentions(c.base(), p) && !mentions(c.args().get(0), p)) {
            return occurrenceDemands(c, op, Term.number(k), env);
        }
        if (c.returnType().equals("boolean")) {
            Boolean whenTrue = Term.Pred.compare(1, op, k), whenFalse = Term.Pred.compare(0, op, k);
            if (whenTrue == null || whenFalse == null || whenTrue.equals(whenFalse)) return null;
            return wrap(booleanApi(c, whenTrue, p, env), soften(booleanKind(c, whenTrue), c));
        }
        if (c.returnType().equals("int") || c.returnType().equals("char") || c.returnType().equals("long")) {
            return wrap(intApi(c, op, Term.number(k), p, env), soften(intKind(c.name(), op), c));
        }
        return null;
    }

    private static Inferred wrap(Lifted l, Kind kind) {
        if (l instanceof ParamRx pr) return new Pattern(pr.rx(), kind);
        if (l instanceof Demand d) return new Demands(List.of(d.withKind(kind)));
        return null;
    }

    private static Rx rxOf(Lifted l) { return l instanceof ParamRx pr ? pr.rx() : null; }

    /** Lifts a regex for the value of {@code t} towards parameter p; for the channel, which resolves partitions itself. */
    public static Lifted lift(Term t, Rx rx, int p, Map<Integer, Object> env) {
        try {
            return pushDown(t, rx, p, env);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * s.indexOf(d) op k and s.lastIndexOf(d) op k as demands on the partition at that occurrence: an
     * accepted index means there is an occurrence and fixes the length of what precedes it; otherwise -1
     * must be accepted and the value has no occurrence.
     */
    private static Inferred occurrenceDemands(Term.Call c, String op, long k, Map<Integer, Object> env) {
        String d = needle(c, env);
        if (d == null) return null;
        boolean first = c.name().equals("indexOf");
        Partition part = new Partition(c.base(), d, d, first ? Partition.Mode.FIRST : Partition.Mode.LAST, 0);
        boolean minusOne = Boolean.TRUE.equals(Term.Pred.compare(-1L, op, k));
        // is some index >= 0 accepted? decided on the smallest candidate per relation, not by counting up to k
        long smallest = op.equals("==") || op.equals(">=") ? Math.max(k, 0) : op.equals(">") ? Math.max(k + 1, 0) : op.equals("!=") && k == 0 ? 1 : 0;
        boolean found = Boolean.TRUE.equals(Term.Pred.compare(smallest, op, k));
        // "i <= 0", "i < 1": both -1 and an index are accepted; the absence of the delimiter is what such a guard is about
        if (found && minusOne && (op.equals("<") || op.equals("<="))) found = false;
        Kind kind = soften(minusOne && found ? Kind.CHOICE : Kind.NECESSARY, c);
        List<Demand> out = new ArrayList<>();
        if (found) {
            out.add(new Demand(part, Demand.Access.COUNT, 0, null, ">=", 2, kind));
            Rx len = lengthRx(op, k);
            if (len != null) out.add(new Demand(part, first ? Demand.Access.AT : Demand.Access.REST, first ? 0 : 1, len, null, 0, kind));
        } else if (minusOne) {
            out.add(new Demand(part, Demand.Access.COUNT, 0, null, "==", 1, kind));
        } else {
            return null;
        }
        return new Demands(out);
    }

    /** The string an indexOf-like call searches for, or null when it is not a non-empty literal under env. */
    private static String needle(Term.Call c, Map<Integer, Object> env) {
        Object x = Term.eval(c.args().get(0), env);
        String t = c.paramTypes().get(0);
        String d = t.equals("int") || t.equals("char") ? String.valueOf((char) Term.number(x)) : text(x);
        return d == null || d.isEmpty() ? null : d;
    }

    private static Kind booleanKind(Term.Call c, boolean truth) {
        switch (c.name()) {
            case "equals": case "contentEquals": case "startsWith": case "endsWith": case "matches":
                return !truth ? Kind.AVOID : c.base() != null && c.base() instanceof Term.Const && !c.name().equals("equals") && !c.name().equals("contentEquals") ? Kind.CHOICE : Kind.NECESSARY;
            case "equalsIgnoreCase": return truth ? Kind.CHOICE : Kind.AVOID;
            case "contains": return truth && !(c.base() instanceof Term.Const) ? Kind.NECESSARY : Kind.CHOICE;
            case "isEmpty": return Kind.NECESSARY;
            case "isBlank": return Kind.CHOICE;
            default: return c.base() == null && c.owner().equals("java.lang.Character") ? Kind.NECESSARY : Kind.CHOICE;
        }
    }

    private static Kind intKind(String name, String op) {
        if (name.equals("length")) return Kind.NECESSARY;   // "!= k" for k > 0 picks k + 1, which Profile treats as a length like any other
        if (name.equals("charAt") || name.equals("codePointAt")) return op.equals("==") || op.equals("!=") ? Kind.NECESSARY : Kind.CHOICE;
        return Kind.CHOICE;
    }

    /** A regex lifted through case conversion, trimming or replacement stands for one of several preimages. */
    private static Kind soften(Kind kind, Term t) {
        return kind == Kind.NECESSARY && passesChoiceApi(t) ? Kind.CHOICE : kind;
    }

    private static boolean passesChoiceApi(Term t) {
        if (t instanceof Term.Call c) {
            switch (c.name()) {
                case "toLowerCase": case "toUpperCase": case "trim": case "strip": case "replace": case "replaceAll": case "replaceFirst": return true;
                default: break;
            }
            if (c.base() != null && passesChoiceApi(c.base())) return true;
            for (Term a : c.args()) if (passesChoiceApi(a)) return true;
            return false;
        }
        if (t instanceof Term.Concat cat) { for (Term a : cat.parts()) if (passesChoiceApi(a)) return true; return false; }
        if (t instanceof Term.Elem e) return passesChoiceApi(e.array());
        return false;
    }

    private static boolean mentions(Term t, int p) {
        if (t == null) return false;
        Set<Integer> s = new HashSet<>();
        Term.params(t, s);
        return s.contains(p);
    }

    private static String text(Object v) {
        if (v instanceof CharSequence cs) return cs.toString();
        return null;
    }

    private static Lifted booleanApi(Term.Call c, boolean truth, int p, Map<Integer, Object> env) {
        String name = c.name();
        if (c.base() == null) {
            // static helpers
            if (c.owner().equals("java.lang.Character") && c.args().size() == 1 && c.args().get(0) instanceof Term.Call at
                    && (at.name().equals("charAt") || at.name().equals("codePointAt")) && at.base() != null && mentions(at.base(), p)) {
                CharCls cls = characterClass(name, truth);
                if (cls == null) return null;
                return pushDown(at.base(), around(at, new Open(1, 1, cls), env), p, env);
            }
            if (c.owner().equals("java.util.Objects") && name.equals("equals") && c.args().size() == 2) {
                Term a = c.args().get(0), b = c.args().get(1);
                Term mine = mentions(a, p) ? a : b, other = mine == a ? b : a;
                if (mentions(other, p)) return null;
                String s = text(Term.eval(other, env));
                return s == null ? null : pushDown(mine, equalsRx(s, truth), p, env);
            }
            if (c.owner().equals("java.util.regex.Pattern") && name.equals("matches") && c.args().size() == 2 && mentions(c.args().get(1), p)) {
                String re = text(Term.eval(c.args().get(0), env));
                return re == null ? null : pushDown(c.args().get(1), matchesRx(re, truth), p, env);
            }
            return null;
        }
        if (!Term.isStringLike(c.owner()) && !c.owner().equals("java.lang.Object")) return null;
        boolean onBase = mentions(c.base(), p);
        if (onBase) {
            for (Term a : c.args()) if (mentions(a, p)) return null;
            switch (name) {
                case "isEmpty": return pushDown(c.base(), truth ? Rx.lit("") : Rx.len(1, -1), p, env);
                case "isBlank": return pushDown(c.base(), truth ? Rx.of(new Open(1, 2, CharCls.SPACE)) : Rx.of(new Open(1, -1, CharCls.NON_SPACE)), p, env);
                default: break;
            }
            if (c.args().isEmpty()) return null;
            String s = text(Term.eval(c.args().get(0), env));
            if (s == null) return null;
            Rx rx;
            switch (name) {
                case "equals": case "contentEquals": rx = equalsRx(s, truth); break;
                case "equalsIgnoreCase": rx = truth ? Rx.lit(swapCase(s)) : Rx.lit(mutate(s)); break;
                case "startsWith":
                    if (c.args().size() == 2) {
                        int off = (int) Term.number(Term.eval(c.args().get(1), env));
                        if (off < 0) return null;
                        rx = Rx.len(off, off).concat(Rx.lit(truth ? s : mutate(s))).concat(Rx.any());
                    } else {
                        if (!truth && s.isEmpty()) return null;
                        rx = Rx.lit(truth ? s : mutate(s)).concat(Rx.any());
                    }
                    break;
                case "endsWith":
                    if (!truth && s.isEmpty()) return null;
                    rx = Rx.any().concat(Rx.lit(truth ? s : mutate(s)));
                    break;
                case "contains":
                    if (!truth && s.isEmpty()) return null;
                    rx = truth ? Rx.any().concat(Rx.lit(s)).concat(Rx.any()) : Rx.of(new Open(0, -1, CharCls.not(s)));
                    break;
                case "matches": rx = matchesRx(s, truth); break;
                default: return null;
            }
            return rx == null ? null : pushDown(c.base(), rx, p, env);
        }
        // "literal".equals(param) and friends: the parameter is the argument
        if (c.args().size() != 1 || !mentions(c.args().get(0), p)) return null;
        String s = text(Term.eval(c.base(), env));
        if (s == null) return null;
        Rx rx;
        switch (name) {
            case "equals": case "contentEquals": rx = equalsRx(s, truth); break;
            case "equalsIgnoreCase": rx = truth ? Rx.lit(swapCase(s)) : Rx.lit(mutate(s)); break;
            case "startsWith": rx = truth ? Rx.lit(s.substring(0, (s.length() + 1) / 2)) : Rx.lit(mutate(s)); break;
            case "endsWith": rx = truth ? Rx.lit(s.substring(s.length() / 2)) : Rx.lit(mutate(s)); break;
            case "contains": rx = truth ? Rx.lit(s.substring(s.length() / 3, s.length() - s.length() / 3)) : Rx.lit(mutate(s) + "#"); break;
            default: return null;
        }
        return pushDown(c.args().get(0), rx, p, env);
    }

    private static Lifted intApi(Term.Call c, String op, long k, int p, Map<Integer, Object> env) {
        String name = c.name();
        if (c.base() == null) {
            // Integer.parseInt(s) and its relatives: a numeral that satisfies the relation
            if ((name.equals("parseInt") || name.equals("parseLong")) && c.args().size() == 1 && mentions(c.args().get(0), p)) {
                return pushDown(c.args().get(0), Rx.lit(String.valueOf(satisfying(op, k))), p, env);
            }
            return null;
        }
        if ((name.equals("intValue") || name.equals("longValue")) && c.base() instanceof Term.Call box && box.base() == null && box.name().equals("valueOf")
                && box.args().size() == 1 && mentions(box.args().get(0), p)) {
            return pushDown(box.args().get(0), Rx.lit(String.valueOf(satisfying(op, k))), p, env);
        }
        if (!Term.isStringLike(c.owner()) || !mentions(c.base(), p)) return null;
        if (name.equals("charAt") || name.equals("codePointAt")) {
            Seg at;
            if (op.equals("!=")) at = new Open(1, 1, CharCls.not(String.valueOf((char) k)));
            else {
                long ch = satisfying(op, k);
                if (ch < 0 || ch > Character.MAX_VALUE) return null;
                at = new Lit(String.valueOf((char) ch));
            }
            return pushDown(c.base(), around(c, at, env), p, env);
        }
        for (Term a : c.args()) if (mentions(a, p)) return null;
        switch (name) {
            case "length": {
                Rx rx = lengthRx(op, k);
                return rx == null ? null : pushDown(c.base(), rx, p, env);
            }
            case "indexOf": case "lastIndexOf": {
                Object x = Term.eval(c.args().get(0), env);
                String s = c.paramTypes().get(0).equals("int") || c.paramTypes().get(0).equals("char") ? String.valueOf((char) Term.number(x)) : text(x);
                if (s == null || s.isEmpty()) return null;
                long from = c.args().size() == 2 ? Math.max(0, Term.number(Term.eval(c.args().get(1), env))) : 0;
                // the smallest index the relation accepts, if any
                long j = -1;
                for (long cand = from; cand <= Math.max(from, k + 1) + 1; cand++) if (Boolean.TRUE.equals(Term.Pred.compare(cand, op, k))) { j = cand; break; }
                if (j >= 0) {
                    boolean exact = op.equals("==");
                    return pushDown(c.base(), Rx.of(new Open((int) j, exact ? (int) j : -1, CharCls.not(s)), new Lit(s), new Open(0, -1, name.equals("lastIndexOf") ? CharCls.not(s) : CharCls.ANY)), p, env);
                }
                if (Boolean.TRUE.equals(Term.Pred.compare(-1L, op, k))) return pushDown(c.base(), Rx.of(new Open(0, -1, CharCls.not(s))), p, env);
                return null;
            }
            case "compareTo": case "compareToIgnoreCase": {
                String s = text(Term.eval(c.args().get(0), env));
                if (s == null) return null;
                for (String cand : List.of(s, s + "a", s.isEmpty() ? s : s.substring(0, s.length() - 1), "", "~" + s)) {
                    if (Boolean.TRUE.equals(Term.Pred.compare((long) cand.compareTo(s), op, k))) return pushDown(c.base(), Rx.lit(cand), p, env);
                }
                return null;
            }
            default: return null;
        }
    }

    /**
     * The caller of charAt with the character segment at the call's index: counted from the start for a
     * closed index, from the end for {@code s.charAt(s.length() - c)}; null for other index shapes.
     */
    private static Rx around(Term.Call charAt, Seg at, Map<Integer, Object> env) {
        Term index = charAt.args().get(0);
        if (index instanceof Term.Bin b && b.op().equals("-") && b.left() instanceof Term.Call len && len.name().equals("length")
                && len.base() != null && len.base().equals(charAt.base())) {
            int back = (int) Term.number(Term.eval(b.right(), env));
            if (back < 1) return null;
            return Rx.of(new Open(0, -1, CharCls.ANY), at, new Open(back - 1, back - 1, CharCls.ANY));
        }
        int i = (int) Term.number(Term.eval(index, env));
        if (i < 0) return null;
        return Rx.of(new Open(i, i, CharCls.ANY), at, new Open(0, -1, CharCls.ANY));
    }

    /** split(d).length op k: a demand on the number of segments. */
    private static Inferred pieces(Term.Call split, String op, long k, int p, Map<Integer, Object> env) {
        if (!mentions(split.base(), p)) return null;
        Partition part = splitPartition(split, env);
        if (part == null) return null;
        return new Demands(List.of(new Demand(part, Demand.Access.COUNT, 0, null, op, k, Kind.NECESSARY)));
    }

    /** The demand "this segment matches rx" for a term of the form {@code x.split(d)[j]} with literal d and j; null for other terms. */
    public static Demand segmentDemand(Term segment, Rx rx, Kind kind, Map<Integer, Object> env) {
        try {
            if (!(segment instanceof Term.Elem e) || !(e.array() instanceof Term.Call split) || !split.name().equals("split") || split.base() == null) return null;
            Partition part = splitPartition(split, env);
            int j = (int) Term.number(Term.eval(e.index(), env));
            return part == null || j < 0 || j > 12 ? null : new Demand(part, Demand.Access.AT, j, rx, null, 0, kind);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Partition splitPartition(Term.Call split, Map<Integer, Object> env) {
        Object pattern = Term.eval(split.args().get(0), env);
        String d = delimiter(pattern);
        if (d == null) return null;
        int limit = split.args().size() == 2 ? (int) Term.number(Term.eval(split.args().get(1), env)) : 0;
        return new Partition(split.base(), d, excluded(text(pattern), d), Partition.Mode.SPLIT, limit);
    }

    /** The characters a piece must avoid: those of the delimiter instance, and sample characters the pattern itself matches. */
    private static String excluded(String pattern, String instance) {
        StringBuilder sb = new StringBuilder(instance);
        try {
            java.util.regex.Pattern re = java.util.regex.Pattern.compile(pattern);
            for (char ch : (CharCls.ANY.alphabet() + "-_ ,;:").toCharArray()) {
                if (sb.indexOf(String.valueOf(ch)) < 0 && re.matcher(String.valueOf(ch)).find()) sb.append(ch);
            }
        } catch (RuntimeException e) {
            // not a pattern java.util.regex accepts: split would throw anyway
        }
        return sb.toString();
    }

    /** An integer v with {@code v op k}. */
    private static long satisfying(String op, long k) {
        switch (op) {
            case "==": case "<=": case ">=": return k;
            case "!=": case ">": return k + 1;
            default: return k - 1;
        }
    }

    private static Rx lengthRx(String op, long k) {
        int n = (int) Math.max(Math.min(k, 10_000), -1);
        switch (op) {
            case "==": return n < 0 ? null : Rx.len(n, n);
            case "!=": return n == 0 ? Rx.len(1, -1) : Rx.len(n + 1, n + 1);
            case "<": return n <= 0 ? null : Rx.len(0, n - 1);
            case "<=": return n < 0 ? null : Rx.len(0, n);
            case ">": return Rx.len(n + 1, -1);
            case ">=": return Rx.len(Math.max(n, 0), -1);
            default: return null;
        }
    }

    private static Rx equalsRx(String s, boolean truth) { return Rx.lit(truth ? s : mutate(s)); }

    private static Rx matchesRx(String regex, boolean truth) {
        if (!RegexStrings.valid(regex)) return null;
        if (truth) return Rx.raw(regex, 0);
        List<String> no = RegexStrings.notMatching(regex, 1, new Random(7));
        return no.isEmpty() ? null : Rx.lit(no.get(0));
    }

    /** A string of the same length that differs from s in its last character. */
    static String mutate(String s) {
        if (s.isEmpty()) return "a";
        char last = s.charAt(s.length() - 1);
        return s.substring(0, s.length() - 1) + (last == 'x' ? 'y' : 'x');
    }

    static String swapCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) sb.append(Character.isUpperCase(ch) ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
        return sb.toString();
    }

    private static CharCls characterClass(String predicate, boolean truth) {
        switch (predicate) {
            case "isDigit": return truth ? CharCls.DIGIT : CharCls.NON_DIGIT;
            case "isLetter": case "isAlphabetic": return truth ? CharCls.LETTER : CharCls.NON_LETTER;
            case "isLetterOrDigit": return truth ? CharCls.ALNUM : CharCls.NON_ALNUM;
            case "isWhitespace": case "isSpaceChar": return truth ? CharCls.SPACE : CharCls.NON_SPACE;
            case "isUpperCase": return truth ? CharCls.UPPER : CharCls.LOWER;
            case "isLowerCase": return truth ? CharCls.LOWER : CharCls.UPPER;
            default: return null;
        }
    }

    /** The literal a split pattern stands for; for a real regular expression, one string it matches; null when there is none. */
    static String delimiter(Object pattern) {
        String d = text(pattern);
        if (d == null || d.isEmpty()) return null;
        if (d.equals("\\s+") || d.equals("\\s") || d.equals("\\s*")) return " ";
        if (d.length() == 2 && d.charAt(0) == '\\' && !Character.isLetterOrDigit(d.charAt(1))) return d.substring(1);
        boolean literal = true;
        for (char ch : d.toCharArray()) if ("\\[](){}.*+?^$|".indexOf(ch) >= 0) literal = false;
        if (literal) return d;
        // a real regular expression: one non-empty instance stands for it (the marker of the split paper's
        // normalization); overlaps and alternative matches are left to the check on the real split
        if (RegexStrings.valid(d)) for (String m : RegexStrings.matching(d, 3, new Random(11))) if (!m.isEmpty()) return m;
        return null;
    }

    // ------------------------------------------------------------------ non-terminated APIs

    /**
     * Inference (Algorithm 4): given that the value of {@code t} must match {@code rx}, a regex for
     * parameter p, obtained by lifting rx through the APIs between the parameter and t.
     */
    static Lifted pushDown(Term t, Rx rx, int p, Map<Integer, Object> env) {
        if (rx == null || t == null) return null;
        if (t instanceof Term.Param param) return param.index() == p ? new ParamRx(rx) : null;
        if (t instanceof Term.Concat cat) {
            int at = -1;
            for (int i = 0; i < cat.parts().size(); i++) {
                if (!mentions(cat.parts().get(i), p)) continue;
                if (at >= 0) return null;
                at = i;
            }
            if (at < 0) return null;
            StringBuilder before = new StringBuilder(), after = new StringBuilder();
            for (int i = 0; i < cat.parts().size(); i++) {
                if (i == at) continue;
                (i < at ? before : after).append(Term.eval(cat.parts().get(i), env));
            }
            Rx inner = rx.stripPrefix(before.toString());
            if (inner != null) inner = inner.stripSuffix(after.toString());
            return pushDown(cat.parts().get(at), inner, p, env);
        }
        if (t instanceof Term.Elem e && e.array() instanceof Term.Call split && split.name().equals("split") && split.base() != null) {
            // not lifted further here: the demands on one split are answered together (structural relaxation)
            Partition part = splitPartition(split, env);
            int j = (int) Term.number(Term.eval(e.index(), env));
            if (part == null || j < 0 || j > 12 || !mentions(split.base(), p)) return null;
            return new Demand(part, Demand.Access.AT, j, rx, null, 0, Kind.CHOICE);
        }
        if (!(t instanceof Term.Call c)) return null;
        String name = c.name();
        if (c.base() == null) {
            // String.valueOf(x), Objects.toString(x), Objects.requireNonNull(x): the argument itself
            if ((name.equals("valueOf") || name.equals("toString") || name.equals("requireNonNull") || name.equals("copyValueOf")) && !c.args().isEmpty()
                    && !c.paramTypes().get(0).endsWith("[]") && !isPrimitive(c.paramTypes().get(0))) return pushDown(c.args().get(0), rx, p, env);
            return null;
        }
        if (!Term.isStringLike(c.owner()) && !c.owner().equals("java.lang.Object")) return null;
        if (!mentions(c.base(), p)) {
            // "prefix".concat(param)
            if (name.equals("concat") && c.args().size() == 1) return pushDown(c.args().get(0), rx.stripPrefix(String.valueOf(Term.eval(c.base(), env))), p, env);
            return null;
        }
        if (name.equals("substring") || name.equals("subSequence")) {
            Lifted around = aroundOccurrence(c, rx, env);
            if (around != null) return around;
        }
        for (Term a : c.args()) if (mentions(a, p)) return null;
        switch (name) {
            case "substring": case "subSequence": {
                int i1 = (int) Term.number(Term.eval(c.args().get(0), env));
                if (i1 < 0) return null;
                RegexWrapper pre;
                if (c.args().size() == 2) {
                    int i2 = (int) Term.number(Term.eval(c.args().get(1), env));
                    if (i2 < i1) return null;
                    pre = new RegexWrapper(Rx.len(i1, i1), i2 - i1, Rx.any(), i1);      // M7
                } else {
                    pre = new RegexWrapper(Rx.len(i1, i1), -1, null, i1);
                }
                RegexWrapper merged = RegexWrapper.merge(pre, RegexWrapper.terminated(rx));
                return merged == null ? null : pushDown(c.base(), merged.r(), p, env);
            }
            case "trim": case "strip": {
                RegexWrapper pre = new RegexWrapper(Rx.of(new Open(0, 1, CharCls.SPACE)), -1, Rx.of(new Open(0, 1, CharCls.SPACE)), 0);   // M10
                return pushDown(c.base(), RegexWrapper.merge(pre, RegexWrapper.terminated(rx)).r(), p, env);
            }
            case "toLowerCase":
                if (!rx.literalsAll(s -> s.equals(s.toLowerCase()))) return null;
                return pushDown(c.base(), rx.mapLiterals(String::toUpperCase), p, env);
            case "toUpperCase":
                if (!rx.literalsAll(s -> s.equals(s.toUpperCase()))) return null;
                return pushDown(c.base(), rx.mapLiterals(String::toLowerCase), p, env);
            case "toString": case "intern": case "replace": case "replaceAll": case "replaceFirst":
                // M9 and M11: identity is the most useful member of the approximation; candidates are checked on the real API afterwards
                return pushDown(c.base(), rx, p, env);
            case "concat":
                return pushDown(c.base(), rx.stripSuffix(String.valueOf(Term.eval(c.args().get(0), env))), p, env);
            default:
                return null;
        }
    }

    /** An index derived from the value it is applied to: base.indexOf(d) + offset, or the same with lastIndexOf. */
    private record Occurrence(Partition.Mode mode, String delim, int offset) {}

    private static Occurrence occurrence(Term index, Term base, Map<Integer, Object> env) {
        Term call = index;
        int offset = 0;
        if (index instanceof Term.Bin b && b.op().equals("+")) {
            boolean left = b.left() instanceof Term.Call;
            call = left ? b.left() : b.right();
            try {
                offset = (int) Term.number(Term.eval(left ? b.right() : b.left(), env));
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (!(call instanceof Term.Call c) || c.base() == null || !c.base().equals(base) || c.args().size() != 1 || offset < 0) return null;
        if (!c.name().equals("indexOf") && !c.name().equals("lastIndexOf")) return null;
        String d;
        try {
            d = needle(c, env);
        } catch (RuntimeException e) {
            return null;
        }
        return d == null ? null : new Occurrence(c.name().equals("indexOf") ? Partition.Mode.FIRST : Partition.Mode.LAST, d, offset);
    }

    /**
     * The key=value idiom. {@code s.substring(0, s.indexOf(d))} is segment 0 of the partition at the first
     * occurrence and {@code s.substring(s.indexOf(d) + |d|)} its remainder; with lastIndexOf the roles
     * are mirrored. Null when the call has another shape.
     */
    private static Lifted aroundOccurrence(Term.Call c, Rx rx, Map<Integer, Object> env) {
        if (c.args().size() == 2) {
            Occurrence o = occurrence(c.args().get(1), c.base(), env);
            if (o == null || o.offset() != 0) return null;
            try {
                if (Term.number(Term.eval(c.args().get(0), env)) != 0) return null;
            } catch (RuntimeException e) {
                return null;
            }
            boolean first = o.mode() == Partition.Mode.FIRST;
            return new Demand(new Partition(c.base(), o.delim(), o.delim(), o.mode(), 0), first ? Demand.Access.AT : Demand.Access.REST, first ? 0 : 1, rx, null, 0, Kind.CHOICE);
        }
        if (c.args().size() != 1) return null;
        Occurrence o = occurrence(c.args().get(0), c.base(), env);
        if (o == null) return null;
        // the value is (delim + after).substring(offset), where after is the side behind the occurrence
        int skip = o.offset() - o.delim().length();
        Rx after = skip >= 0 ? Rx.len(skip, skip).concat(rx) : rx.stripPrefix(o.delim().substring(o.offset()));
        if (after == null) return null;
        boolean first = o.mode() == Partition.Mode.FIRST;
        return new Demand(new Partition(c.base(), o.delim(), o.delim(), o.mode(), 0), first ? Demand.Access.REST : Demand.Access.AT, first ? 1 : 0, after, null, 0, Kind.CHOICE);
    }

    private static boolean isPrimitive(String t) {
        switch (t) {
            case "int": case "long": case "char": case "boolean": case "short": case "byte": case "double": case "float": return true;
            default: return false;
        }
    }
}
