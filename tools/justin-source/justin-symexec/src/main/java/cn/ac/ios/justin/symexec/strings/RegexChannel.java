package cn.ac.ios.justin.symexec.strings;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Regex-guided input generation for string parameters (the JustinStr method): for every target, a few
 * paths to it are sliced into predicates over the parameters, each string predicate is turned into a
 * regex for its parameter through the API-Regex mapping, strings are drawn from the regexes, and the
 * draw that satisfies the most predicates of the path, checked by evaluating the sliced terms on
 * the real JDK APIs, becomes the input. Exception-triggering variants make the caller of substring,
 * charAt or split(d)[j] too short while keeping the predicates before the fault site.
 *
 * <p>Conjunctions of predicates on one parameter are built by {@link Profile} for the shapes the mapping
 * produces, and every candidate, conjoined or drawn from a single regex, goes through the check. Integer,
 * char and boolean parameters that occur in string predicates take values from a short list, and the
 * string parameters are solved under each choice.
 */
public final class RegexChannel {
    private static final Logger log = LoggerFactory.getLogger(RegexChannel.class);
    public static final String NAME = "regex";

    private static final int MAX_TARGETS = 40, PATHS_PER_TARGET = 3, MAX_PATHS = 60, MAX_FAULT_VARIANTS = 3, INT_ROUNDS = 6;
    private static final long[] INTS = {1, 0, 2, 3, 5, -1}, CHARS = {'a', '0', ' ', 'A', 'z', '-'}, BOOLS = {1, 0};

    /** One solved path: a value per mentioned parameter (null = the null reference), and how it was obtained. */
    public record Solution(Map<Integer, Object> values, boolean exact, boolean faultVariant, int targetLine) {}

    /**
     * @param patterns per string parameter, the regexes inferred from the predicates on the sliced paths
     * @param samples  per string parameter, strings drawn from those regexes (the values of the virtual pattern annotation)
     */
    public record Result(List<Solution> solutions, Map<Integer, Set<String>> patterns, Map<Integer, List<String>> samples, int paths, int stringPaths) {
        static final Result EMPTY = new Result(List.of(), Map.of(), Map.of(), 0, 0);
    }

    private final Body body;
    private final java.util.function.Function<sootup.core.signatures.MethodSignature, java.util.Optional<Body>> resolver;
    private final Map<Integer, String> paramTypes = new TreeMap<>();
    private final Random rnd;
    private final Map<Integer, Set<String>> patterns = new TreeMap<>();
    private final Map<Integer, Set<Rx>> inferred = new TreeMap<>();

    public RegexChannel(Body body, long seed) { this(body, seed, null); }

    /** @param resolver bodies of project methods, so predicates that call static helpers can be expanded; may be null */
    public RegexChannel(Body body, long seed, java.util.function.Function<sootup.core.signatures.MethodSignature, java.util.Optional<Body>> resolver) {
        this.resolver = resolver;
        this.body = body;
        this.rnd = new Random(seed);
        for (Stmt s : body.getStmts()) if (s instanceof JIdentityStmt id && id.getRightOp() instanceof JParameterRef pr) paramTypes.put(pr.getIndex(), pr.getType().toString());
    }

    /** True when the method has a parameter the channel can solve for. */
    public boolean applicable() {
        for (String t : paramTypes.values()) if (Term.isStringLike(t)) return true;
        return false;
    }

    public Map<Integer, String> paramTypes() { return paramTypes; }

    /**
     * @param targetLines lines to reach; empty with {@code direct} means both outcomes of every branch
     */
    public Result generate(Set<Integer> targetLines, boolean direct) {
        if (!applicable()) return Result.EMPTY;
        PathSlicer slicer = new PathSlicer(body, resolver);
        List<Stmt> targets = new ArrayList<>();
        Map<Stmt, Integer> lineOfTarget = new HashMap<>();
        if (direct || targetLines.isEmpty()) {
            for (Stmt s : slicer.allBranchOutcomes()) { targets.add(s); lineOfTarget.put(s, PathSlicer.lineOf(s)); }
        } else {
            for (int line : new TreeSet<>(targetLines)) for (Stmt s : slicer.targetStmts(line)) if (lineOfTarget.putIfAbsent(s, line) == null) targets.add(s);
        }
        if (targets.size() > MAX_TARGETS) targets = targets.subList(0, MAX_TARGETS);
        // target lines that are not in this body belong to callees (the engine counts same-class callee lines as targets)
        List<PathSlicer.StringPath> viaCalls = new ArrayList<>();
        if (!direct) {
            Set<Integer> here = new HashSet<>(lineOfTarget.values());
            for (int line : new TreeSet<>(targetLines)) {
                if (here.contains(line) || viaCalls.size() >= MAX_PATHS / 2) continue;
                for (var site : slicer.callSitesReaching(line).entrySet()) viaCalls.addAll(slicer.throughCall(site.getKey(), site.getValue(), line, 4));
            }
        }

        List<PathSlicer.StringPath> paths = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        int allPaths = 0;
        for (Stmt t : targets) {
            if (paths.size() >= MAX_PATHS) break;
            for (List<Stmt> p : slicer.pathsTo(t, PATHS_PER_TARGET)) {
                allPaths++;
                PathSlicer.StringPath sp = slicer.slice(p, lineOfTarget.get(t));
                if (log.isTraceEnabled()) log.trace("path to line {}: {}", lineOfTarget.get(t), sp == null ? null : sp.preds());
                if (sp == null) continue;
                for (PathSlicer.StringPath x : slicer.expand(sp)) if (mentionsString(x) && seenPaths.add(x.preds().toString())) paths.add(x);
            }
        }

        for (PathSlicer.StringPath sp : viaCalls) {
            allPaths++;
            for (PathSlicer.StringPath x : slicer.expand(sp)) if (paths.size() < MAX_PATHS && mentionsString(x) && seenPaths.add(x.preds().toString())) paths.add(x);
        }

        List<Solution> out = new ArrayList<>();
        Set<Map<Integer, Object>> seen = new HashSet<>();
        for (PathSlicer.StringPath sp : paths) {
            Solution s = solve(sp.preds(), null, sp.targetLine());
            if (s != null && seen.add(s.values())) out.add(s);
        }
        // exception-triggering variants: distinct fault sites, earliest first
        Set<Term> sites = new LinkedHashSet<>();
        int variants = 0;
        for (PathSlicer.StringPath sp : paths) {
            for (PathSlicer.FaultSite f : sp.faultSites()) {
                if (variants >= MAX_FAULT_VARIANTS || !sites.add(f.call())) continue;
                List<Term.Pred> before = new ArrayList<>();
                for (Term.Pred p : sp.preds()) if (p.position() < f.position()) before.add(p);
                Solution s = solve(before, f, f.line());
                if (s != null && seen.add(s.values())) { out.add(s); variants++; }
            }
        }
        if (log.isDebugEnabled()) log.debug("regex channel: {} targets, {} paths, {} string paths, {} solutions, patterns {}", targets.size(), allPaths, paths.size(), out.size(), patterns);
        Map<Integer, List<String>> samples = new TreeMap<>();
        for (var e : inferred.entrySet()) {
            List<String> drawn = new ArrayList<>();
            for (Rx rx : e.getValue()) {
                if (drawn.size() >= 8) break;
                for (int slack : new int[] {0, 3}) {
                    String s = rx.sample(rnd, slack);
                    if (s != null && !drawn.contains(s)) drawn.add(s);
                }
            }
            samples.put(e.getKey(), drawn);
        }
        return new Result(out, patterns, samples, allPaths, paths.size());
    }

    private boolean mentionsString(PathSlicer.StringPath sp) {
        for (Term.Pred p : sp.preds()) for (int i : p.params()) if (Term.isStringLike(paramTypes.getOrDefault(i, ""))) return true;
        for (PathSlicer.FaultSite f : sp.faultSites()) {
            Set<Integer> ps = new HashSet<>();
            Term.params(f.call(), ps);
            for (int i : ps) if (Term.isStringLike(paramTypes.getOrDefault(i, ""))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ solving one path

    private Solution solve(List<Term.Pred> preds, PathSlicer.FaultSite fault, int targetLine) {
        Set<Integer> mentioned = new TreeSet<>();
        for (Term.Pred p : preds) mentioned.addAll(p.params());
        if (fault != null) Term.params(fault.call(), mentioned);
        List<Integer> strings = new ArrayList<>(), ints = new ArrayList<>();
        for (int i : mentioned) {
            String t = paramTypes.getOrDefault(i, "");
            if (Term.isStringLike(t)) strings.add(i);
            else if (t.equals("int") || t.equals("char") || t.equals("boolean") || t.equals("short") || t.equals("byte") || t.equals("long")) ints.add(i);
        }
        if (strings.isEmpty()) return null;

        Map<Integer, Object> best = null;
        int bestFalse = Integer.MAX_VALUE;
        boolean inferredAny = false;
        int rounds = ints.isEmpty() ? 1 : INT_ROUNDS;
        for (int round = 0; round < rounds; round++) {
            Map<Integer, Object> env = new LinkedHashMap<>();
            for (int n = 0; n < ints.size(); n++) {
                List<Long> pool = intCandidates(ints.get(n), preds);
                env.put(ints.get(n), (int) (long) pool.get((round + (round == 0 ? 0 : n)) % pool.size()));
            }
            List<Integer> todo = new ArrayList<>(strings);
            while (!todo.isEmpty()) {
                int p = pickNext(todo, preds, env);
                todo.remove((Integer) p);
                inferredAny |= assign(p, preds, fault, env);
            }
            int falses = 0;
            for (Term.Pred pr : preds) if (Boolean.FALSE.equals(pr.check(env))) falses++;
            if (fault != null && !throwsAt(fault, env)) falses += 100;
            if (falses < bestFalse) { bestFalse = falses; best = env; }
            if (falses == 0) break;
        }
        if (best == null || !inferredAny || bestFalse >= 100) return null;
        return new Solution(best, bestFalse == 0, fault != null, targetLine);
    }

    /**
     * Values for an integer, char or boolean parameter: first what the path's direct comparisons with
     * constants ask for (wrapChar == 0, max > 3), then the defaults.
     */
    private List<Long> intCandidates(int p, List<Term.Pred> preds) {
        List<Long> out = new ArrayList<>();
        for (Term.Pred pr : preds) {
            boolean left = pr.left() instanceof Term.Param x && x.index() == p, right = pr.right() instanceof Term.Param y && y.index() == p;
            if (left == right) continue;
            Term other = left ? pr.right() : pr.left();
            if (!(other instanceof Term.Const c) || c.value() == null || c.value() instanceof String) continue;
            long k = Term.number(c.value());
            String op = left ? pr.effectiveOp() : Term.Pred.flip(pr.effectiveOp());
            long v = op.equals("==") || op.equals("<=") || op.equals(">=") ? k : op.equals("!=") || op.equals(">") ? k + 1 : k - 1;
            if (!out.contains(v)) out.add(v);
        }
        String t = paramTypes.get(p);
        for (long d : t.equals("char") ? CHARS : t.equals("boolean") ? BOOLS : INTS) if (!out.contains(d)) out.add(d);
        return out;
    }

    /**
     * A parameter whose predicates mention only parameters that already have a value; else the one the
     * mapping can say the most about right now (in startsWith(prefix) && prefix.length() >= 2 that is the
     * prefix, and the key follows once the prefix is known).
     */
    private static int pickNext(List<Integer> todo, List<Term.Pred> preds, Map<Integer, Object> env) {
        for (int p : todo) {
            boolean ready = true;
            for (Term.Pred pr : preds) {
                Set<Integer> ps = pr.params();
                if (!ps.contains(p)) continue;
                for (int q : ps) if (q != p && !env.containsKey(q)) { ready = false; break; }
                if (!ready) break;
            }
            if (ready) return p;
        }
        int best = todo.get(0), bestCount = -1;
        for (int p : todo) {
            int count = 0;
            for (Term.Pred pr : preds) if (pr.params().contains(p) && ApiRegexMapping.infer(pr, p, env) != null) count++;
            if (count > bestCount) { bestCount = count; best = p; }
        }
        return best;
    }

    private static boolean throwsAt(PathSlicer.FaultSite f, Map<Integer, Object> env) {
        try {
            Term.eval(f.call(), env);
            return false;
        } catch (Term.Threw e) {
            return true;
        } catch (Term.NotEvaluable e) {
            return false;
        }
    }

    private static final int SEGMENT_ROUNDS = 3;

    /**
     * Chooses the value of string parameter p under env; true when a predicate contributed a regex or a
     * null demand. Predicates that relate two segments of one split (parts[0].length() ==
     * parts[1].length()) say nothing about p directly; after a first candidate they are refined segment by
     * segment, see {@link #refineSegments}.
     */
    private boolean assign(int p, List<Term.Pred> preds, PathSlicer.FaultSite fault, Map<Integer, Object> env) {
        List<Term.Pred> mine = new ArrayList<>();
        for (Term.Pred pr : preds) if (pr.params().contains(p)) mine.add(pr);
        List<Rx> rxs = new ArrayList<>(), necessary = new ArrayList<>(), combinable = new ArrayList<>();
        Map<ApiRegexMapping.Partition, List<ApiRegexMapping.Demand>> demands = new LinkedHashMap<>();
        boolean wantNull = false;
        for (Term.Pred pr : mine) {
            ApiRegexMapping.Inferred inf = ApiRegexMapping.infer(pr, p, env);
            if (inf instanceof ApiRegexMapping.IsNull) wantNull = true;
            else if (inf instanceof ApiRegexMapping.Demands ds) for (ApiRegexMapping.Demand d : ds.list()) demands.computeIfAbsent(d.part(), k -> new ArrayList<>()).add(d);
            else if (inf instanceof ApiRegexMapping.Pattern pat && !rxs.contains(pat.rx())) {
                rxs.add(pat.rx());
                if (pat.kind() == ApiRegexMapping.Kind.NECESSARY) { necessary.add(pat.rx()); combinable.add(pat.rx()); }
                else if (pat.kind() == ApiRegexMapping.Kind.CHOICE) combinable.add(pat.rx());
                patterns.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(pat.rx().toRegex());
                if (pat.kind() != ApiRegexMapping.Kind.AVOID) inferred.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(pat.rx());
            }
        }
        if (fault != null) {
            Rx v = ApiRegexMapping.faultVariant(fault.call(), p, env);
            if (v != null) { rxs.add(0, v); necessary.add(0, v); combinable.add(0, v); }
        }

        Object bestValue = null;
        int bestScore = Integer.MIN_VALUE;
        boolean inferredAny = false;
        Set<Object> tried = new HashSet<>();
        for (int round = 0; round <= SEGMENT_ROUNDS; round++) {
            List<Rx> structured = resolvePartitions(demands, p, env);
            List<Object> candidates = new ArrayList<>();
            if (wantNull) candidates.add(null);
            // the conjunction of what the path demands of p: everything combinable, then the necessary part alone
            List<Rx> all = new ArrayList<>(combinable);
            all.addAll(structured);
            for (List<Rx> group : List.of(all, necessary)) {
                String s = group.size() < 2 ? null : Profile.conjoin(group);
                if (s != null && !candidates.contains(s)) candidates.add(s);
            }
            List<Rx> single = new ArrayList<>(structured);
            single.addAll(rxs);
            for (Rx rx : single) {
                for (int slack : new int[] {0, 2, 4, 4}) {
                    String s = rx.sample(rnd, slack);
                    if (s != null && !candidates.contains(s)) candidates.add(s);
                }
            }
            inferredAny |= !candidates.isEmpty();
            if (round == 0) for (String d : List.of("a", "", "ab 1")) if (!candidates.contains(d)) candidates.add(d);

            for (Object cand : candidates) {
                if (!tried.add(cand == null ? NULL_CANDIDATE : cand)) continue;
                env.put(p, cand);
                int score = 0;
                for (Term.Pred pr : mine) {
                    Boolean ok = pr.check(env);
                    if (ok == null) continue;
                    score += ok ? 1 : -100;
                }
                if (fault != null && throwsAt(fault, env)) score += 50;
                if (score > bestScore) { bestScore = score; bestValue = cand; }
            }
            env.put(p, bestValue);
            if (bestScore >= 0 || !refineSegments(p, mine, env, demands)) break;
        }
        env.put(p, bestValue);
        return inferredAny;
    }

    private static final Object NULL_CANDIDATE = new Object();

    /**
     * Structural relaxation: one expression per partition for all the demands on it, innermost partition
     * first (its base is the larger term), so that what an inner split needs becomes a demand on the outer
     * one. Returns the expressions that reached parameter p.
     */
    private List<Rx> resolvePartitions(Map<ApiRegexMapping.Partition, List<ApiRegexMapping.Demand>> demands, int p, Map<Integer, Object> env) {
        Map<ApiRegexMapping.Partition, List<ApiRegexMapping.Demand>> open = new LinkedHashMap<>();
        for (var e : demands.entrySet()) open.put(e.getKey(), new ArrayList<>(e.getValue()));
        List<Rx> out = new ArrayList<>();
        for (int guard = 0; !open.isEmpty() && guard < 8; guard++) {
            ApiRegexMapping.Partition part = null;
            for (ApiRegexMapping.Partition q : open.keySet()) if (part == null || q.base().toString().length() > part.base().toString().length()) part = q;
            Rx whole = SegmentProfile.build(part, open.remove(part));
            if (whole == null) continue;
            ApiRegexMapping.Lifted lifted = ApiRegexMapping.lift(part.base(), whole, p, env);
            if (lifted instanceof ApiRegexMapping.Demand outer) open.computeIfAbsent(outer.part(), k -> new ArrayList<>()).add(outer);
            else if (lifted instanceof ApiRegexMapping.ParamRx pr && !out.contains(pr.rx())) {
                out.add(pr.rx());
                patterns.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(pr.rx().toRegex());
                inferred.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(pr.rx());
            }
        }
        return out;
    }

    /**
     * Guess, check, refine on segments. A failing predicate that mentions several segments of a split is
     * rewritten with all but the last of them replaced by the values they have in the current candidate;
     * what is left is an ordinary demand on one segment. The replaced segments are pinned to those values.
     * True when a demand was added, so another candidate is worth building.
     */
    private static boolean refineSegments(int p, List<Term.Pred> mine, Map<Integer, Object> env, Map<ApiRegexMapping.Partition, List<ApiRegexMapping.Demand>> demands) {
        boolean added = false;
        for (Term.Pred pr : mine) {
            if (!Boolean.FALSE.equals(pr.check(env))) continue;
            List<Term.Elem> segments = new ArrayList<>();
            for (Term side : List.of(pr.left(), pr.right())) {
                Term.visit(side, t -> {
                    if (t instanceof Term.Elem e && e.array() instanceof Term.Call c && c.name().equals("split") && e.index() instanceof Term.Const && !segments.contains(e)) segments.add(e);
                });
            }
            if (segments.size() < 2) continue;
            Term.Elem unknown = segments.get(0);
            for (Term.Elem e : segments) if (index(e) > index(unknown)) unknown = e;
            Term left = pr.left(), right = pr.right();
            List<ApiRegexMapping.Demand> pinned = new ArrayList<>();
            boolean ok = true;
            for (Term.Elem e : segments) {
                if (e.equals(unknown)) continue;
                Object v;
                try {
                    v = Term.eval(e, env);
                } catch (RuntimeException ex) {
                    ok = false;
                    break;
                }
                if (!(v instanceof String text)) { ok = false; break; }
                left = Term.replace(left, e, new Term.Const(text));
                right = Term.replace(right, e, new Term.Const(text));
                ApiRegexMapping.Demand pin = ApiRegexMapping.segmentDemand(e, Rx.lit(text), ApiRegexMapping.Kind.NECESSARY, env);
                if (pin != null) pinned.add(pin);
            }
            if (!ok) continue;
            if (!(ApiRegexMapping.infer(new Term.Pred(left, pr.op(), right, pr.holds(), pr.position()), p, env) instanceof ApiRegexMapping.Demands ds)) continue;
            List<ApiRegexMapping.Demand> fresh = new ArrayList<>(ds.list());
            fresh.addAll(pinned);
            for (ApiRegexMapping.Demand d : fresh) {
                List<ApiRegexMapping.Demand> list = demands.computeIfAbsent(d.part(), k -> new ArrayList<>());
                if (!list.contains(d)) { list.add(d); added = true; }
            }
        }
        return added;
    }

    private static long index(Term.Elem e) { return e.index() instanceof Term.Const c && c.value() instanceof Number n ? n.longValue() : -1; }

    // ------------------------------------------------------------------ values

    /** The solved value of parameter i as an input value, or null when the solution does not fix it. */
    public Value valueOf(Solution s, int i, MethodTarget m) {
        if (!s.values().containsKey(i)) return null;
        Object v = s.values().get(i);
        String t = paramTypes.getOrDefault(i, "");
        if (v == null) return new Value.NullValue(m.params().get(i).type());
        if (v instanceof String str) {
            if (Term.BUILDERS.contains(t)) {
                String cls = t.equals("java.lang.StringBuffer") ? t : "java.lang.StringBuilder";
                return new Value.ObjectValue(TypeRef.ClassRef.of(cls), new Value.Recipe.ConstructorCall(cls, "(Ljava/lang/String;)V", List.of(new Value.StringValue(str))));
            }
            return new Value.StringValue(str);
        }
        long n = Term.number(v);
        switch (t) {
            case "int": return Value.PrimitiveValue.of((int) n);
            case "char": return Value.PrimitiveValue.of((char) n);
            case "boolean": return Value.PrimitiveValue.of(n != 0);
            case "short": return Value.PrimitiveValue.of((short) n);
            case "byte": return Value.PrimitiveValue.of((byte) n);
            case "long": return Value.PrimitiveValue.of(n);
            default: return null;
        }
    }
}
