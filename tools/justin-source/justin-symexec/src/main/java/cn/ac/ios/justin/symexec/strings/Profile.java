package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.symexec.strings.Rx.CharCls;
import cn.ac.ios.justin.symexec.strings.Rx.Lit;
import cn.ac.ios.justin.symexec.strings.Rx.Open;
import cn.ac.ios.justin.symexec.strings.Rx.Seg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Conjunction of several regexes on one string, for the shapes the API-Regex mapping produces: fixed
 * characters counted from the start, fixed characters counted from the end, literals that occur
 * somewhere, a length range and an alphabet for the rest. Regexes are not closed under intersection in
 * a form one can sample from cheaply; the profile is, and what it builds is checked against the real
 * predicates afterwards, so an unsupported shape is skipped rather than approximated.
 */
final class Profile {

    enum Added { OK, UNSUPPORTED, CONFLICT }

    /** One position: a fixed character, or any character of a class. */
    private record Spec(Character literal, CharCls cls) {}

    private final Map<Integer, Spec> fromStart = new TreeMap<>(), fromEnd = new TreeMap<>();
    private final List<String> contains = new ArrayList<>();
    private int minLen = 0, maxLen = -1;
    private String alphabet = CharCls.ANY.alphabet();

    Added add(Rx rx) {
        List<Seg> segs = new ArrayList<>();
        for (Seg s : rx.segs()) {
            if (s instanceof Rx.Raw) return Added.UNSUPPORTED;
            segs.add(s);
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) if (segs.get(i) instanceof Open o && o.max() == -1) gaps.add(i);
        if (gaps.size() > 2) return Added.UNSUPPORTED;
        Profile trial = copy();
        Added r;
        if (segs.size() == 1 && segs.get(0) instanceof Open o && o.cls().equals(CharCls.ANY)) {
            r = trial.length(o.min(), o.max());     // a pure length constraint
        } else if (segs.isEmpty()) {
            r = trial.length(0, 0);
        } else if (gaps.isEmpty()) {
            List<Spec> all = specs(segs, 0, segs.size());
            r = trial.place(all, true);
            if (r == Added.OK) r = trial.length(all.size(), all.size());
        } else {
            int first = gaps.get(0), last = gaps.get(gaps.size() - 1);
            List<Spec> head = specs(segs, 0, first), tail = specs(segs, last + 1, segs.size());
            r = trial.place(head, true);
            if (r == Added.OK) r = trial.place(tail, false);
            int min = head.size() + tail.size();
            for (int g : gaps) {
                Open o = (Open) segs.get(g);
                min += o.min();
                if (!o.cls().equals(CharCls.ANY)) trial.restrict(o.cls().alphabet());
            }
            if (gaps.size() == 2) {
                StringBuilder mid = new StringBuilder();
                for (int i = first + 1; i < last; i++) {
                    if (!(segs.get(i) instanceof Lit l)) return Added.UNSUPPORTED;
                    mid.append(l.text());
                }
                trial.contains.add(mid.toString());
                min += mid.length();
            }
            if (r == Added.OK) r = trial.length(min, -1);
        }
        if (r != Added.OK) return r;
        if (trial.alphabet.isEmpty()) return Added.CONFLICT;
        adopt(trial);
        return Added.OK;
    }

    private Added length(int min, int max) {
        minLen = Math.max(minLen, min);
        if (max != -1) maxLen = maxLen == -1 ? max : Math.min(maxLen, max);
        return maxLen != -1 && minLen > maxLen ? Added.CONFLICT : Added.OK;
    }

    private void restrict(String allowed) {
        StringBuilder sb = new StringBuilder();
        for (char c : alphabet.toCharArray()) if (allowed.indexOf(c) >= 0) sb.append(c);
        alphabet = sb.toString();
    }

    /** Positions of the segments in [from, to), bounded flexible segments at their minimum. */
    private static List<Spec> specs(List<Seg> segs, int from, int to) {
        List<Spec> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Seg s = segs.get(i);
            if (s instanceof Lit l) for (char c : l.text().toCharArray()) out.add(new Spec(c, null));
            else if (s instanceof Open o) for (int k = 0; k < o.min(); k++) out.add(new Spec(null, o.cls()));
        }
        return out;
    }

    private Added place(List<Spec> specs, boolean start) {
        for (int i = 0; i < specs.size(); i++) {
            int index = start ? i : specs.size() - 1 - i;
            Map<Integer, Spec> map = start ? fromStart : fromEnd;
            Spec merged = meet(map.get(index), specs.get(i));
            if (merged == null) return Added.CONFLICT;
            map.put(index, merged);
        }
        return Added.OK;
    }

    private static Spec meet(Spec a, Spec b) {
        if (a == null) return b;
        if (a.literal() != null && b.literal() != null) return a.literal().equals(b.literal()) ? a : null;
        if (a.literal() != null) return admits(b.cls(), a.literal()) ? a : null;
        if (b.literal() != null) return admits(a.cls(), b.literal()) ? b : null;
        if (a.cls().equals(CharCls.ANY)) return b;
        if (b.cls().equals(CharCls.ANY)) return a;
        StringBuilder both = new StringBuilder();
        for (char c : a.cls().alphabet().toCharArray()) if (admits(b.cls(), c)) both.append(c);
        for (char c : b.cls().alphabet().toCharArray()) if (admits(a.cls(), c) && both.indexOf(String.valueOf(c)) < 0) both.append(c);
        return both.length() == 0 ? null : new Spec(null, new CharCls("(?=" + a.cls().regex() + ")" + b.cls().regex(), both.toString()));
    }

    private static boolean admits(CharCls cls, char c) {
        try {
            return java.util.regex.Pattern.matches(cls.regex(), String.valueOf(c));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A string with every recorded property, or null when they do not fit into one string. */
    String build() {
        int startExtent = fromStart.isEmpty() ? 0 : ((TreeMap<Integer, Spec>) fromStart).lastKey() + 1;
        int endExtent = fromEnd.isEmpty() ? 0 : ((TreeMap<Integer, Spec>) fromEnd).lastKey() + 1;
        int middle = 0;
        for (String c : contains) middle += c.length();
        int roomy = Math.max(minLen, startExtent + middle + endExtent);
        // first without overlaps; when the length limit forbids that, let the fixed parts share positions
        for (int len : maxLen != -1 && roomy > maxLen ? new int[] {maxLen} : new int[] {roomy}) {
            if (len < Math.max(startExtent, endExtent) || len < minLen) continue;
            Spec[] at = new Spec[len];
            boolean ok = true;
            for (var e : fromStart.entrySet()) at[e.getKey()] = e.getValue();
            for (var e : fromEnd.entrySet()) {
                int i = len - 1 - e.getKey();
                Spec m = meet(at[i], e.getValue());
                if (m == null) { ok = false; break; }
                at[i] = m;
            }
            if (!ok) continue;
            char[] out = new char[len];
            boolean[] fixed = new boolean[len];
            for (int i = 0; i < len; i++) if (at[i] != null && at[i].literal() != null) { out[i] = at[i].literal(); fixed[i] = true; }
            // literals that must occur: in the free stretch after the start part, unless the fixed text already has them
            int cursor = startExtent;
            for (String c : contains) {
                if (new String(out).contains(c) && !c.isEmpty()) continue;
                int pos = -1;
                for (int i = cursor; i + c.length() <= len - endExtent; i++) {
                    boolean free = true;
                    for (int k = 0; k < c.length(); k++) if (at[i + k] != null) { free = false; break; }
                    if (free) { pos = i; break; }
                }
                if (pos < 0) { ok = false; break; }
                for (int k = 0; k < c.length(); k++) { out[pos + k] = c.charAt(k); fixed[pos + k] = true; at[pos + k] = new Spec(c.charAt(k), null); }
                cursor = pos + c.length();
            }
            if (!ok) continue;
            int n = 0;
            for (int i = 0; i < len; i++) {
                if (fixed[i]) continue;
                String pool = alphabet;
                if (at[i] != null && at[i].cls() != null && !at[i].cls().equals(CharCls.ANY)) {
                    StringBuilder both = new StringBuilder();
                    for (char c : at[i].cls().alphabet().toCharArray()) if (alphabet.indexOf(c) >= 0) both.append(c);
                    pool = both.length() > 0 ? both.toString() : at[i].cls().alphabet();
                }
                if (pool.isEmpty()) { ok = false; break; }
                out[i] = pool.charAt(n++ % pool.length());
            }
            if (ok) return new String(out);
        }
        return null;
    }

    private Profile copy() {
        Profile p = new Profile();
        p.fromStart.putAll(fromStart);
        p.fromEnd.putAll(fromEnd);
        p.contains.addAll(contains);
        p.minLen = minLen;
        p.maxLen = maxLen;
        p.alphabet = alphabet;
        return p;
    }

    private void adopt(Profile p) {
        fromStart.clear(); fromStart.putAll(p.fromStart);
        fromEnd.clear(); fromEnd.putAll(p.fromEnd);
        contains.clear(); contains.addAll(p.contains);
        minLen = p.minLen;
        maxLen = p.maxLen;
        alphabet = p.alphabet;
    }

    /** The string that satisfies as many of the given regexes as fit together, taken in order; null when none could be used. */
    static String conjoin(List<Rx> rxs) {
        Profile p = new Profile();
        int used = 0;
        for (Rx rx : rxs) if (p.add(rx) == Added.OK) used++;
        return used == 0 ? null : p.build();
    }
}
