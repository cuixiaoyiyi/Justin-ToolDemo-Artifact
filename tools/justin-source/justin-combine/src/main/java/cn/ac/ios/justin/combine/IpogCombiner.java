package cn.ac.ios.justin.combine;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.ValuePool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * n-way covering array by IPOG (In-Parameter-Order-General) over the legal candidates of every slot,
 * with mixed domain sizes. Strength 1 degenerates to each-choice, strength k to all combinations.
 * Illegal candidates are appended afterwards, one per tuple, exactly as in EachChoiceCombiner.
 *
 * Domain sizes are capped (candidate order = priority, so annotation and special values survive);
 * rows are emitted in construction order and truncated to the budget, and the result reports the
 * fraction of strength-n interactions the emitted rows actually cover.
 */
public final class IpogCombiner implements Combiner {

    private final int strength;
    private final int maxDomain;

    public IpogCombiner(int strength, int maxDomain) {
        this.strength = Math.max(1, strength);
        this.maxDomain = Math.max(2, maxDomain);
    }

    @Override
    public CombineResult combine(MethodTarget target, List<ValuePool> pools, Map<String, TypeRef> typeBindings, int maxTuples, long seed,
                                 Predicate<List<Candidate>> allowed) {
        boolean hasReceiver = !pools.isEmpty() && pools.get(0).slot() == -1;
        if (hasReceiver && pools.get(0).isEmpty()) return empty();
        for (ValuePool p : pools) if (p.isEmpty()) return empty();
        int k = pools.size();
        Random rnd = new Random(seed ^ target.id().hashCode());

        // legal domains, capped
        List<List<Candidate>> dom = new ArrayList<>();
        for (ValuePool p : pools) {
            List<Candidate> l = new ArrayList<>(p.candidates().stream().filter(Candidate::legal).toList());
            if (l.isEmpty()) l = new ArrayList<>(p.candidates());
            // the cap keeps interaction counts manageable for strength >= 2; each-choice keeps everything
            if (strength >= 2 && l.size() > maxDomain) l = new ArrayList<>(l.subList(0, maxDomain));
            dom.add(l);
        }
        int t = Math.min(strength, k);

        // parameter order: largest domains first (standard IPOG heuristic)
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(dom.get(b).size(), dom.get(a).size()));
        int[] ord = Arrays.stream(order).mapToInt(Integer::intValue).toArray();

        // rows are int[k] of candidate indices; -1 = don't care
        List<int[]> rows = new ArrayList<>();
        if (k == 0) return empty();
        // 1. all combinations of the first t parameters
        int[] first = Arrays.copyOfRange(ord, 0, t);
        for (int[] combo : allCombinations(first, dom)) {
            int[] row = new int[k];
            Arrays.fill(row, -1);
            for (int i = 0; i < t; i++) row[first[i]] = combo[i];
            rows.add(row);
        }
        // 2. add parameters one by one
        for (int pi = t; pi < k; pi++) {
            int p = ord[pi];
            int[] previous = Arrays.copyOfRange(ord, 0, pi);
            Set<Interaction> uncovered = interactionsWith(p, previous, t, dom);
            // horizontal extension
            for (int[] row : rows) {
                int best = -1;
                int bestGain = -1;
                for (int v = 0; v < dom.get(p).size(); v++) {
                    row[p] = v;
                    if (!allowed.test(candidates(row, dom))) continue;
                    int gain = countCovered(row, p, uncovered);
                    if (gain > bestGain) { bestGain = gain; best = v; }
                }
                row[p] = best < 0 ? 0 : best;
                removeCovered(row, p, uncovered);
            }
            // vertical extension
            List<int[]> added = new ArrayList<>();
            for (Interaction in : new ArrayList<>(uncovered)) {
                if (!uncovered.contains(in)) continue;
                int[] host = null;
                for (int[] row : added) {
                    if (in.fitsInto(row)) { host = row; break; }
                }
                if (host == null) {
                    host = new int[k];
                    Arrays.fill(host, -1);
                    added.add(host);
                }
                in.writeInto(host);
                uncovered.remove(in);
            }
            rows.addAll(added);
        }
        // 3. fill don't-cares, check constraints, then order rows greedily by the number of not yet
        //    covered interactions each adds, so a truncated budget keeps the most valuable rows
        List<int[]> valid = new ArrayList<>();
        for (int[] row : rows) {
            for (int i = 0; i < k; i++) if (row[i] < 0) row[i] = rnd.nextInt(dom.get(i).size());
            if (allowed.test(candidates(row, dom))) valid.add(row);
        }
        List<int[]> subsetsT = subsets(k, t);
        List<int[]> ordered = greedyOrder(valid, subsetsT, Math.min(maxTuples, valid.size()));
        List<InputTuple> out = new ArrayList<>();
        Set<List<Value>> seen = new HashSet<>();
        for (int[] row : ordered) {
            if (out.size() >= maxTuples) break;
            addRow(target, candidates(row, dom), hasReceiver, typeBindings, out, seen, "ipog" + t);
        }
        // random legal rows fill whatever budget the covering array left unused
        int attempts = 0;
        while (out.size() < maxTuples && attempts++ < maxTuples * 4) {
            int[] row = new int[k];
            for (int i = 0; i < k; i++) row[i] = rnd.nextInt(dom.get(i).size());
            List<Candidate> cs = candidates(row, dom);
            if (allowed.test(cs)) addRow(target, cs, hasReceiver, typeBindings, out, seen, "random-fill");
        }
        int legalRows = out.size();
        // achieved coverage over all strength-t interactions of the capped domains
        long total = 0, covered = 0;
        List<int[]> emitted = new ArrayList<>();
        for (InputTuple tp : out) emitted.add(indicesOf(tp, hasReceiver, dom));
        for (int[] subset : subsetsT) {
            Set<List<Integer>> cov = new HashSet<>();
            for (int[] row : emitted) {
                List<Integer> key = new ArrayList<>();
                for (int s : subset) key.add(row[s]);
                cov.add(key);
            }
            long all = 1;
            for (int s : subset) all *= dom.get(s).size();
            total += all;
            covered += Math.min(all, cov.size());
        }
        // 4. illegal candidates, each alone in a tuple built from legal values
        int illegalBudget = maxTuples;
        int illegal = 0;
        for (int s = 0; s < k && illegal < illegalBudget; s++) {
            for (Candidate bad : pools.get(s).candidates()) {
                if (bad.legal() || illegal >= illegalBudget) continue;
                List<Candidate> row = new ArrayList<>();
                for (int i = 0; i < k; i++) row.add(i == s ? bad : dom.get(i).get(rnd.nextInt(dom.get(i).size())));
                if (!allowed.test(row)) continue;
                int before = out.size();
                addRow(target, row, hasReceiver, typeBindings, out, seen, "illegal:" + slotName(target, pools.get(s).slot()));
                if (out.size() > before) illegal++;
            }
        }
        return new CombineResult(out, t, total, covered, legalRows, illegal);
    }

    private static CombineResult empty() { return new CombineResult(List.of(), 0, 0, 0, 0, 0); }

    /** Greedy set cover over interactions: repeatedly take the row covering the most still-uncovered ones. */
    private static List<int[]> greedyOrder(List<int[]> rows, List<int[]> subsets, int wanted) {
        List<Set<String>> keys = new ArrayList<>();
        for (int[] row : rows) {
            Set<String> ks = new HashSet<>();
            for (int[] subset : subsets) {
                StringBuilder sb = new StringBuilder();
                for (int s : subset) sb.append(s).append('=').append(row[s]).append(',');
                ks.add(sb.toString());
            }
            keys.add(ks);
        }
        Set<String> covered = new HashSet<>();
        boolean[] used = new boolean[rows.size()];
        List<int[]> out = new ArrayList<>();
        while (out.size() < wanted) {
            int best = -1, bestGain = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (used[i]) continue;
                int gain = 0;
                for (String key : keys.get(i)) if (!covered.contains(key)) gain++;
                if (gain > bestGain) { bestGain = gain; best = i; }
            }
            if (best < 0) break;
            used[best] = true;
            covered.addAll(keys.get(best));
            out.add(rows.get(best));
        }
        return out;
    }

    // ---------- helpers ----------

    /** A strength-t interaction: parameter indices with their chosen candidate indices. */
    private record Interaction(int[] params, int[] values) {
        boolean fitsInto(int[] row) {
            for (int i = 0; i < params.length; i++) if (row[params[i]] >= 0 && row[params[i]] != values[i]) return false;
            return true;
        }
        void writeInto(int[] row) { for (int i = 0; i < params.length; i++) row[params[i]] = values[i]; }
        boolean coveredBy(int[] row) {
            for (int i = 0; i < params.length; i++) if (row[params[i]] != values[i]) return false;
            return true;
        }
        @Override public boolean equals(Object o) {
            return o instanceof Interaction i && Arrays.equals(params, i.params) && Arrays.equals(values, i.values);
        }
        @Override public int hashCode() { return 31 * Arrays.hashCode(params) + Arrays.hashCode(values); }
    }

    /** All interactions of strength t that involve p and t-1 of the previous parameters. */
    private static Set<Interaction> interactionsWith(int p, int[] previous, int t, List<List<Candidate>> dom) {
        Set<Interaction> out = new HashSet<>();
        for (int[] others : subsets(previous.length, t - 1)) {
            int[] params = new int[t];
            for (int i = 0; i < t - 1; i++) params[i] = previous[others[i]];
            params[t - 1] = p;
            Arrays.sort(params);
            for (int[] vals : allCombinations(params, dom)) out.add(new Interaction(params, vals));
        }
        return out;
    }

    private static int countCovered(int[] row, int p, Set<Interaction> uncovered) {
        int n = 0;
        for (Interaction in : uncovered) if (in.coveredBy(row)) n++;
        return n;
    }

    private static void removeCovered(int[] row, int p, Set<Interaction> uncovered) {
        uncovered.removeIf(in -> in.coveredBy(row));
    }

    /** Every combination of candidate indices for the given parameters. */
    private static List<int[]> allCombinations(int[] params, List<List<Candidate>> dom) {
        List<int[]> out = new ArrayList<>();
        int[] cur = new int[params.length];
        combos(params, dom, 0, cur, out);
        return out;
    }

    private static void combos(int[] params, List<List<Candidate>> dom, int i, int[] cur, List<int[]> out) {
        if (i == params.length) { out.add(cur.clone()); return; }
        for (int v = 0; v < dom.get(params[i]).size(); v++) { cur[i] = v; combos(params, dom, i + 1, cur, out); }
    }

    /** All index subsets of size t from 0..n-1. */
    static List<int[]> subsets(int n, int t) {
        List<int[]> out = new ArrayList<>();
        if (t == 0) { out.add(new int[0]); return out; }
        if (t > n) return out;
        int[] idx = new int[t];
        for (int i = 0; i < t; i++) idx[i] = i;
        while (true) {
            out.add(idx.clone());
            int i = t - 1;
            while (i >= 0 && idx[i] == n - t + i) i--;
            if (i < 0) break;
            idx[i]++;
            for (int j = i + 1; j < t; j++) idx[j] = idx[j - 1] + 1;
        }
        return out;
    }

    private static List<Candidate> candidates(int[] row, List<List<Candidate>> dom) {
        List<Candidate> cs = new ArrayList<>(row.length);
        for (int i = 0; i < row.length; i++) cs.add(row[i] < 0 ? dom.get(i).get(0) : dom.get(i).get(row[i]));
        return cs;
    }

    private static int[] indicesOf(InputTuple t, boolean hasReceiver, List<List<Candidate>> dom) {
        int k = dom.size();
        int[] row = new int[k];
        for (int i = 0; i < k; i++) {
            Value v = hasReceiver ? (i == 0 ? t.receiver() : t.args().get(i - 1)) : t.args().get(i);
            int idx = -1;
            List<Candidate> d = dom.get(i);
            for (int j = 0; j < d.size(); j++) if (d.get(j).value().equals(v)) { idx = j; break; }
            row[i] = idx;
        }
        return row;
    }

    private static void addRow(MethodTarget t, List<Candidate> row, boolean hasReceiver, Map<String, TypeRef> bindings,
                               List<InputTuple> out, Set<List<Value>> seen, String source) {
        List<Value> values = new ArrayList<>(row.size());
        Set<String> sources = new TreeSet<>();
        for (Candidate c : row) { values.add(c.value()); sources.add(c.source()); }
        if (!seen.add(values)) return;
        Value recv = hasReceiver ? values.get(0) : null;
        List<Value> args = hasReceiver ? values.subList(1, values.size()) : values;
        out.add(new InputTuple(t, recv, args, bindings, source, sources));
    }

    private static String slotName(MethodTarget t, int slot) {
        return slot < 0 ? "this" : t.params().get(slot).name();
    }
}
