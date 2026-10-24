package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.symexec.strings.ApiRegexMapping.Demand;
import cn.ac.ios.justin.symexec.strings.ApiRegexMapping.Kind;
import cn.ac.ios.justin.symexec.strings.ApiRegexMapping.Partition;
import cn.ac.ios.justin.symexec.strings.Rx.CharCls;
import cn.ac.ios.justin.symexec.strings.Rx.Lit;
import cn.ac.ios.justin.symexec.strings.Rx.Open;
import cn.ac.ios.justin.symexec.strings.Rx.Seg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Structural relaxation (Han et al., "Solving String Split Constraints via Structural Relaxation")
 * on the generation side. The predicates of a path look at a delimiter-induced structure through a
 * few accessors: a segment, the remainder, the number of segments. All demands on one partition are
 * answered by one expression {@code x0 d x1 d ... xk tail}: only the segments up to the greatest
 * index the path refers to are materialized, each free of the delimiter, segments nobody constrains
 * get a short filler, and the tail stays empty unless a count demand asks for more segments.
 *
 * <p>The paper needs depth guards because a formula may reach an index under a disjunction; a path is
 * a conjunction, so the depth is simply the greatest index. Its guess-check-refine loop for variable
 * indices is the channel's own: integer parameters are given values first and every candidate is
 * checked on the real API.
 *
 * <p>The same structure serves {@code s.indexOf(d)} with {@code substring}: FIRST is the split at the
 * first occurrence (segment 0 is free of d, the rest is arbitrary), LAST its mirror image for
 * {@code lastIndexOf}.
 */
final class SegmentProfile {
    private SegmentProfile() {}

    private static final int MAX_SEGMENTS = 12;

    /** An expression for the value that is partitioned, satisfying every demand, or null when they do not fit together. */
    static Rx build(Partition part, List<Demand> demands) {
        String d = part.delim(), ex = part.exclude();
        Map<Integer, List<Demand>> at = new TreeMap<>();
        List<Demand> rest = new ArrayList<>();
        int min = 1, max = -1;
        for (Demand x : demands) {
            switch (x.access()) {
                case AT: at.computeIfAbsent(x.index(), k -> new ArrayList<>()).add(x); break;
                case REST: rest.add(x); break;
                default: {
                    int n = (int) Math.max(-1, Math.min(x.n(), 1000));
                    switch (x.op()) {
                        case "==": min = Math.max(min, n); max = max == -1 ? n : Math.min(max, n); break;
                        case ">=": min = Math.max(min, n); break;
                        case ">": min = Math.max(min, n + 1); break;
                        case "<=": max = max == -1 ? n : Math.min(max, n); break;
                        case "<": max = max == -1 ? n - 1 : Math.min(max, n - 1); break;
                        default: break;   // "!=": settled below, once the other bounds are known
                    }
                }
            }
        }
        if (part.mode() != Partition.Mode.SPLIT) return firstOrLast(part, at, rest, min, max);

        int depth = at.isEmpty() ? 0 : ((TreeMap<Integer, List<Demand>>) at).lastKey() + 1;
        min = Math.max(min, depth);
        if (part.limit() > 0) max = max == -1 ? part.limit() : Math.min(max, part.limit());
        for (Demand x : demands) if (x.access() == Demand.Access.COUNT && x.op().equals("!=") && min == x.n()) min++;
        if (max != -1 && min > max || min > MAX_SEGMENTS) return null;
        int total = min;
        List<Rx> segments = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            List<Demand> here = at.get(i);
            Rx seg = here == null ? Rx.of(new Open(1, 2, CharCls.not(ex))) : segment(here, d, ex, true);
            if (seg == null) return null;
            segments.add(seg);
        }
        // limit 0 (the one-argument split) drops trailing empty segments: the last materialized one must not be empty
        if (part.limit() == 0 && total > 1 && segments.get(total - 1).minLength() == 0) {
            if (max == -1 || total < max) { segments.add(Rx.of(new Open(1, 2, CharCls.not(ex)))); }
            else {
                String s = Profile.conjoin(List.of(segments.get(total - 1), Rx.len(1, -1)));
                if (s == null || s.contains(d)) return null;
                segments.set(total - 1, Rx.lit(s));
            }
        }
        List<Seg> out = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) out.add(new Lit(d));
            out.addAll(segments.get(i).segs());
        }
        return Rx.of(out);
    }

    /** x = x0 d rest (FIRST) or x = head d xlast (LAST); without an occurrence the value is one delimiter-free piece. */
    private static Rx firstOrLast(Partition part, Map<Integer, List<Demand>> at, List<Demand> rest, int min, int max) {
        String d = part.delim();
        boolean absent = max == 1;
        if (absent && min > 1) return null;
        if (absent) return Rx.of(new Open(0, -1, CharCls.not(d)));
        Rx piece = at.containsKey(0) ? segment(at.get(0), d, d, true) : Rx.of(new Open(0, -1, CharCls.not(d)));
        Rx other = rest.isEmpty() ? Rx.any() : segment(rest, d, d, false);
        if (piece == null || other == null) return null;
        Rx delim = Rx.lit(d);
        return part.mode() == Partition.Mode.FIRST ? piece.concat(delim).concat(other) : other.concat(delim).concat(piece);
    }

    /**
     * One segment under all the demands on it. Witnesses of negative outcomes count only when nothing
     * positive is asked of the segment; several positive demands are conjoined into one string.
     */
    private static Rx segment(List<Demand> demands, String d, String exclude, boolean delimiterFree) {
        List<Rx> wanted = new ArrayList<>();
        for (Demand x : demands) if (x.kind() != Kind.AVOID && !wanted.contains(x.rx())) wanted.add(x.rx());
        if (wanted.isEmpty()) for (Demand x : demands) if (!wanted.contains(x.rx())) wanted.add(x.rx());
        if (delimiterFree) {
            for (Rx rx : wanted) for (Seg s : rx.segs()) if (s instanceof Lit l && l.text().contains(d)) return null;
        }
        if (wanted.size() > 1) {
            String s = Profile.conjoin(wanted);
            if (s != null && !(delimiterFree && s.contains(d))) return Rx.lit(s);
        }
        return delimiterFree ? wanted.get(0).without(exclude) : wanted.get(0);
    }
}
