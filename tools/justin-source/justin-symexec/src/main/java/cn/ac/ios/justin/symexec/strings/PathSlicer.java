package cn.ac.ios.justin.symexec.strings;

import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Immediate;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.LongConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JAddExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JDynamicInvokeExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JSubExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Path slicing and SAIS construction (Algorithms 1 and 2 of the regex-guided method) on Jimple. A
 * path is a statement sequence from the method entry to a target statement; every branch on it
 * becomes a predicate whose operands are sliced backwards along the path into {@link Term}s over the
 * parameters. The slice of a StringBuilder or StringBuffer is its mutation history on the path,
 * normalized into a concatenation.
 */
public final class PathSlicer {

    /** A string API call on the path that throws for short inputs: the seed of an exception-triggering variant. */
    public record FaultSite(Term call, int position, int line) {}

    /** The predicates of one path in path order, and the fault sites passed on the way. */
    public record StringPath(List<Term.Pred> preds, List<FaultSite> faultSites, int targetLine) {}

    private final Body body;
    private final StmtGraph<?> graph;
    private final List<Stmt> stmts;
    private final Function<MethodSignature, Optional<Body>> resolver;
    private final Map<Term.Call, MethodSignature> signatures = new HashMap<>();
    private final Map<MethodSignature, List<CalleePath>> calleePaths = new HashMap<>();
    private final Map<MethodSignature, PathSlicer> calleeSlicers = new HashMap<>();

    public PathSlicer(Body body) { this(body, null); }

    /** @param resolver bodies of project methods, for expanding helper calls in predicates; null to leave them opaque */
    public PathSlicer(Body body, Function<MethodSignature, Optional<Body>> resolver) {
        this.body = body;
        this.graph = body.getStmtGraph();
        this.stmts = body.getStmts();
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------ paths

    /**
     * Statements to aim at for a target line: the first statement of the line, and both successors of
     * every branch on it (a line is also a target while one of its branches is missed).
     */
    public List<Stmt> targetStmts(int line) {
        LinkedHashSet<Stmt> out = new LinkedHashSet<>();
        boolean first = true;
        for (Stmt s : stmts) {
            if (lineOf(s) != line) continue;
            if (first) { out.add(s); first = false; }
            if (s instanceof JIfStmt || s instanceof JSwitchStmt) out.addAll(graph.successors(s));
        }
        return new ArrayList<>(out);
    }

    /** Both outcomes of every branch of the method: the targets of direct mode. */
    public List<Stmt> allBranchOutcomes() {
        LinkedHashSet<Stmt> out = new LinkedHashSet<>();
        for (Stmt s : stmts) if (s instanceof JIfStmt || s instanceof JSwitchStmt) out.addAll(graph.successors(s));
        return new ArrayList<>(out);
    }

    /**
     * Up to k paths from the entry to the target: the shortest one, then for the branches on it (last
     * first) the detour through the other successor when the target stays reachable from there; a
     * detour through a loop body yields the path with one iteration.
     */
    public List<List<Stmt>> pathsTo(Stmt target, int k) {
        List<List<Stmt>> out = new ArrayList<>();
        Stmt start = graph.getStartingStmt();
        List<Stmt> shortest = shortest(start, target, Set.of());
        if (shortest == null) return out;
        out.add(shortest);
        Set<Stmt> reaches = backwardReachable(target);
        for (int i = shortest.size() - 2; i >= 0 && out.size() < k; i--) {
            Stmt s = shortest.get(i);
            if (!(s instanceof JIfStmt) && !(s instanceof JSwitchStmt)) continue;
            for (Stmt alt : graph.successors(s)) {
                if (alt == shortest.get(i + 1) || !reaches.contains(alt) || out.size() >= k) continue;
                // the detour may come back through the prefix: that is one more iteration of a loop, and the
                // slicer reads definitions by position, so the second visit of a statement sees the updated values
                List<Stmt> tail = shortest(alt, target, Set.of());
                if (tail == null) continue;
                List<Stmt> p = new ArrayList<>(shortest.subList(0, i + 1));
                p.addAll(tail);
                out.add(p);
            }
        }
        return out;
    }

    private List<Stmt> shortest(Stmt from, Stmt to, Set<Stmt> avoid) {
        Map<Stmt, Stmt> parent = new HashMap<>();
        Deque<Stmt> queue = new ArrayDeque<>();
        queue.add(from);
        parent.put(from, from);
        while (!queue.isEmpty()) {
            Stmt s = queue.poll();
            if (s == to) {
                List<Stmt> path = new ArrayList<>();
                for (Stmt x = to; ; x = parent.get(x)) { path.add(0, x); if (x == from) break; }
                return path;
            }
            for (Stmt n : graph.successors(s)) {
                if (avoid.contains(n) || parent.containsKey(n)) continue;
                parent.put(n, s);
                queue.add(n);
            }
        }
        return null;
    }

    private Set<Stmt> backwardReachable(Stmt target) {
        Set<Stmt> seen = new HashSet<>();
        Deque<Stmt> work = new ArrayDeque<>();
        work.add(target);
        seen.add(target);
        while (!work.isEmpty()) for (Stmt p : graph.predecessors(work.poll())) if (seen.add(p)) work.add(p);
        return seen;
    }

    // ------------------------------------------------------------------ SAIS construction

    /** Slices every branch predicate and fault site of the path; null when no predicate involves a parameter. */
    public StringPath slice(List<Stmt> path, int targetLine) {
        List<Term.Pred> preds = predsOf(path);
        List<FaultSite> faults = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Stmt s = path.get(i);
            AbstractInvokeExpr inv = invokeOf(s);
            if (inv instanceof AbstractInstanceInvokeExpr ii && Term.isStringLike(ii.getMethodSignature().getDeclClassType().toString())) {
                String n = ii.getMethodSignature().getName();
                if (n.equals("substring") || n.equals("charAt") || n.equals("subSequence")) {
                    Term call = callTerm(ii, path, i, 0);
                    if (!Term.hasUnknown(call)) faults.add(new FaultSite(call, i, lineOf(s)));
                }
            }
            if (s instanceof JAssignStmt as && as.getRightOp() instanceof JArrayRef ref) {
                Term elem = term(ref, path, i, 0);
                if (elem instanceof Term.Elem e && e.array() instanceof Term.Call c && c.name().equals("split") && !Term.hasUnknown(elem)) faults.add(new FaultSite(elem, i, lineOf(s)));
            }
        }
        boolean relevant = false;
        for (Term.Pred p : preds) if (!p.params().isEmpty()) { relevant = true; break; }
        if (!relevant && faults.isEmpty()) return null;
        return new StringPath(preds, faults, targetLine);
    }

    // ------------------------------------------------------------------ targets inside callees

    /** Call sites of the body whose callee has a body and contains the line, with that callee's slicer. */
    public Map<Stmt, PathSlicer> callSitesReaching(int line) {
        Map<Stmt, PathSlicer> out = new java.util.LinkedHashMap<>();
        if (resolver == null) return out;
        for (Stmt s : stmts) {
            AbstractInvokeExpr inv = invokeOf(s);
            if (inv == null || inv instanceof JDynamicInvokeExpr) continue;
            MethodSignature sig = inv.getMethodSignature();
            String owner = sig.getDeclClassType().toString();
            if (owner.startsWith("java.") || owner.startsWith("javax.") || sig.getName().equals("<init>")) continue;
            PathSlicer callee = calleeSlicers.computeIfAbsent(sig, k -> {
                try {
                    return resolver.apply(k).filter(b -> b.getStmts().size() <= 400).map(b -> new PathSlicer(b, null)).orElse(null);
                } catch (RuntimeException e) {
                    return null;
                }
            });
            if (callee == null || callee.body == body) continue;
            for (Stmt cs : callee.stmts) if (lineOf(cs) == line) { out.put(s, callee); break; }
        }
        return out;
    }

    /**
     * Paths to a line of a callee: the caller's predicates up to the call site, then the callee's
     * predicates up to the line with the call's arguments substituted for the callee's parameters, so
     * the whole path is over the caller's parameters (the worker methods behind a public facade).
     */
    public List<StringPath> throughCall(Stmt callSite, PathSlicer callee, int line, int limit) {
        List<StringPath> out = new ArrayList<>();
        AbstractInvokeExpr inv = invokeOf(callSite);
        if (inv == null) return out;
        List<List<Term.Pred>> inner = new ArrayList<>();
        for (Stmt t : callee.targetStmts(line)) {
            for (List<Stmt> p : callee.pathsTo(t, 2)) if (inner.size() < limit) inner.add(callee.predsOf(p));
        }
        if (inner.isEmpty()) return out;
        for (List<Stmt> p : pathsTo(callSite, 2)) {
            int at = p.size() - 1;
            List<Term> args = new ArrayList<>();
            for (Immediate a : inv.getArgs()) args.add(term(a, p, at, 0));
            List<Term.Pred> outer = predsOf(p);
            for (List<Term.Pred> in : inner) {
                if (out.size() >= limit) return out;
                List<Term.Pred> all = new ArrayList<>(outer);
                for (Term.Pred q : in) all.add(new Term.Pred(substitute(q.left(), args), q.op(), substitute(q.right(), args), q.holds(), at));
                boolean relevant = false;
                for (Term.Pred q : all) if (!q.params().isEmpty()) { relevant = true; break; }
                if (relevant) out.add(new StringPath(all, List.of(), line));
            }
        }
        return out;
    }

    /** The branch predicates of a path, in order. */
    List<Term.Pred> predsOf(List<Stmt> path) {
        List<Term.Pred> preds = new ArrayList<>();
        for (int i = 0; i + 1 < path.size(); i++) {
            Stmt s = path.get(i);
            if (s instanceof JIfStmt ifs) {
                AbstractConditionExpr cond = ifs.getCondition();
                List<Stmt> targets = body.getBranchTargetsOf(ifs);
                boolean taken = !targets.isEmpty() && targets.get(0) == path.get(i + 1);
                preds.add(new Term.Pred(term(cond.getOp1(), path, i, 0), cond.getSymbol().trim(), term(cond.getOp2(), path, i, 0), taken, i));
            } else if (s instanceof JSwitchStmt sw) {
                switchPreds(sw, path, i, preds);
            }
        }
        return preds;
    }

    // ------------------------------------------------------------------ helper calls

    /** One path through a helper method: its predicates and the value it returns, over the helper's parameters. */
    private record CalleePath(List<Term.Pred> preds, Term result) {}

    private static final int CALLEE_PATHS = 4, MAX_EXPANSIONS = 4;

    /**
     * Predicates that call a static project method (StringUtils.isEmpty(cs), length(cs), ...) are opaque
     * to the mapping. Each such call is replaced by what the helper returns along one of its paths, and
     * that path's predicates join the caller's, with the arguments substituted for the helper's
     * parameters: one alternative path per helper path whose result is compatible with the predicate.
     * One level deep; the input path is returned alone when nothing can be expanded.
     */
    public List<StringPath> expand(StringPath path) {
        if (resolver == null) return List.of(path);
        List<List<Term.Pred>> partial = new ArrayList<>();
        partial.add(new ArrayList<>());
        boolean expanded = false;
        for (Term.Pred pred : path.preds()) {
            List<List<Term.Pred>> alternatives = alternativesFor(pred);
            if (alternatives == null) { for (List<Term.Pred> p : partial) p.add(pred); continue; }
            expanded = true;
            List<List<Term.Pred>> next = new ArrayList<>();
            for (List<Term.Pred> p : partial) {
                for (List<Term.Pred> alt : alternatives) {
                    if (next.size() >= MAX_EXPANSIONS) break;
                    List<Term.Pred> q = new ArrayList<>(p);
                    q.addAll(alt);
                    next.add(q);
                }
            }
            if (!next.isEmpty()) partial = next;
            else for (List<Term.Pred> p : partial) p.add(pred);
        }
        if (!expanded) return List.of(path);
        List<StringPath> out = new ArrayList<>();
        for (List<Term.Pred> p : partial) out.add(new StringPath(p, path.faultSites(), path.targetLine()));
        return out;
    }

    /** The replacements of a predicate that contains a helper call, or null when it contains none that can be expanded. */
    private List<List<Term.Pred>> alternativesFor(Term.Pred pred) {
        Term.Call helper = findHelper(pred.left());
        if (helper == null) helper = findHelper(pred.right());
        if (helper == null) return null;
        List<CalleePath> paths = calleePaths.computeIfAbsent(signatures.get(helper), this::pathsThrough);
        if (paths.isEmpty()) return null;
        List<List<Term.Pred>> out = new ArrayList<>();
        for (CalleePath cp : paths) {
            Term result = substitute(cp.result(), helper.args());
            Term.Pred replaced = new Term.Pred(Term.replace(pred.left(), helper, result), pred.op(), Term.replace(pred.right(), helper, result), pred.holds(), pred.position());
            // a helper path whose constant result contradicts the predicate is not an alternative
            if (Boolean.FALSE.equals(replaced.check(Map.of()))) continue;
            List<Term.Pred> alt = new ArrayList<>();
            for (Term.Pred p : cp.preds()) {
                alt.add(new Term.Pred(substitute(p.left(), helper.args()), p.op(), substitute(p.right(), helper.args()), p.holds(), pred.position()));
            }
            if (replaced.check(Map.of()) == null) alt.add(replaced);
            out.add(alt);
        }
        return out.isEmpty() ? null : out;
    }

    private Term.Call findHelper(Term t) {
        if (t instanceof Term.Call c) {
            if (signatures.containsKey(c)) return c;
            if (c.base() != null) { Term.Call h = findHelper(c.base()); if (h != null) return h; }
            for (Term a : c.args()) { Term.Call h = findHelper(a); if (h != null) return h; }
        } else if (t instanceof Term.Concat cat) {
            for (Term a : cat.parts()) { Term.Call h = findHelper(a); if (h != null) return h; }
        } else if (t instanceof Term.Elem e) {
            Term.Call h = findHelper(e.array());
            return h != null ? h : findHelper(e.index());
        } else if (t instanceof Term.Len l) {
            return findHelper(l.array());
        } else if (t instanceof Term.Bin b) {
            Term.Call h = findHelper(b.left());
            return h != null ? h : findHelper(b.right());
        }
        return null;
    }

    private List<CalleePath> pathsThrough(MethodSignature sig) {
        List<CalleePath> out = new ArrayList<>();
        try {
            Body callee = sig == null ? null : resolver.apply(sig).orElse(null);
            if (callee == null || callee.getStmts().size() > 120) return out;
            PathSlicer inner = new PathSlicer(callee, null);
            for (Stmt s : callee.getStmts()) {
                if (!(s instanceof JReturnStmt ret)) continue;
                for (List<Stmt> p : inner.pathsTo(s, 2)) {
                    if (out.size() >= CALLEE_PATHS) return out;
                    out.add(new CalleePath(inner.predsOf(p), inner.term(ret.getOp(), p, p.size() - 1, 0)));
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    /** Parameter i of the helper becomes the i-th argument of the call. */
    private static Term substitute(Term t, List<Term> args) {
        if (t instanceof Term.Param p) return p.index() < args.size() ? args.get(p.index()) : new Term.Unknown("argument " + p.index());
        return Term.map(t, x -> substitute(x, args));
    }

    private void switchPreds(JSwitchStmt sw, List<Stmt> path, int i, List<Term.Pred> preds) {
        List<Stmt> targets = body.getBranchTargetsOf(sw);
        List<IntConstant> values = sw.getValues();
        Term key = term(sw.getKey(), path, i, 0);
        Stmt next = path.get(i + 1);
        for (int v = 0; v < values.size() && v < targets.size(); v++) {
            if (targets.get(v) == next) {
                preds.add(new Term.Pred(key, "==", new Term.Const(values.get(v).getValue()), true, i));
                return;
            }
        }
        // default: none of the case values
        for (int v = 0; v < values.size() && v < 8; v++) preds.add(new Term.Pred(key, "!=", new Term.Const(values.get(v).getValue()), true, i));
    }

    private static AbstractInvokeExpr invokeOf(Stmt s) {
        if (s instanceof JInvokeStmt is) return is.getInvokeExpr().orElse(null);
        if (s instanceof JAssignStmt as && as.getRightOp() instanceof AbstractInvokeExpr e) return e;
        return null;
    }

    private static final int MAX_DEPTH = 40;

    /** The value of v just before path[pos] executes, as a term over the parameters. */
    private Term term(Value v, List<Stmt> path, int pos, int depth) {
        if (depth > MAX_DEPTH) return new Term.Unknown("slice too deep");
        if (v instanceof IntConstant c) return new Term.Const(c.getValue());
        if (v instanceof LongConstant c) return new Term.Const(c.getValue());
        if (v instanceof StringConstant c) return new Term.Const(c.getValue());
        if (v instanceof NullConstant) return new Term.Const(null);
        if (v instanceof Local l) return local(l, path, pos, depth);
        if (v instanceof JParameterRef pr) return new Term.Param(pr.getIndex(), pr.getType().toString());
        if (v instanceof JCastExpr c) return term(c.getOp(), path, pos, depth + 1);
        if (v instanceof JLengthExpr le) return new Term.Len(term(le.getOp(), path, pos, depth + 1));
        if (v instanceof JArrayRef ar) return new Term.Elem(term(ar.getBase(), path, pos, depth + 1), term(ar.getIndex(), path, pos, depth + 1));
        if (v instanceof JAddExpr a) return new Term.Bin("+", term(a.getOp1(), path, pos, depth + 1), term(a.getOp2(), path, pos, depth + 1));
        if (v instanceof JSubExpr a) return new Term.Bin("-", term(a.getOp1(), path, pos, depth + 1), term(a.getOp2(), path, pos, depth + 1));
        if (v instanceof JDynamicInvokeExpr dyn) return concatRecipe(dyn, path, pos, depth);
        if (v instanceof AbstractInvokeExpr inv) return callTerm(inv, path, pos, depth);
        return new Term.Unknown(v.getClass().getSimpleName());
    }

    private Term local(Local l, List<Stmt> path, int pos, int depth) {
        // by origin as well as by type: locals of a body loaded without full type inference carry no precise type
        if (Term.BUILDERS.contains(l.getType().toString()) || rootOf(l, path, pos, depth) != null) return builderContent(l, path, pos, depth);
        int d = lastDef(l, path, pos);
        if (d < 0) return new Term.Unknown("no definition of " + l.getName() + " on the path");
        Stmt def = path.get(d);
        if (def instanceof JIdentityStmt id) return term(id.getRightOp(), path, d, depth + 1);
        return term(((JAssignStmt) def).getRightOp(), path, d, depth + 1);
    }

    private static int lastDef(Local l, List<Stmt> path, int before) {
        for (int i = before - 1; i >= 0; i--) {
            Stmt s = path.get(i);
            if (s instanceof JAssignStmt as && as.getLeftOp() instanceof Local x && x.getName().equals(l.getName())) return i;
            if (s instanceof JIdentityStmt id && id.getLeftOp().getName().equals(l.getName())) return i;
        }
        return -1;
    }

    private Term callTerm(AbstractInvokeExpr inv, List<Stmt> path, int pos, int depth) {
        var sig = inv.getMethodSignature();
        String owner = sig.getDeclClassType().toString();
        List<String> ptypes = new ArrayList<>();
        for (Type t : sig.getParameterTypes()) ptypes.add(t.toString());
        List<Term> args = new ArrayList<>();
        for (Immediate a : inv.getArgs()) args.add(term(a, path, pos, depth + 1));
        Term base = inv instanceof AbstractInstanceInvokeExpr ii ? term(ii.getBase(), path, pos, depth + 1) : null;
        // a builder converted to a String is its content: the boundary between mutable and immutable reasoning
        if (base != null && Term.BUILDERS.contains(owner) && sig.getName().equals("toString")) return base;
        Term.Call call = new Term.Call(owner, sig.getName(), ptypes, base, args, sig.getType().toString());
        if (base == null && !Term.EVALUABLE.contains(owner)) signatures.put(call, sig);
        return call;
    }

    /** Java 9+ string concatenation: the recipe's tag character 1 marks a dynamic argument, tag 2 a bootstrap constant. */
    private Term concatRecipe(JDynamicInvokeExpr dyn, List<Stmt> path, int pos, int depth) {
        if (!dyn.getBootstrapMethodSignature().getName().equals("makeConcatWithConstants") || dyn.getBootstrapArgCount() < 1
                || !(dyn.getBootstrapArg(0) instanceof StringConstant recipe)) return new Term.Unknown("invokedynamic");
        List<Term> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int arg = 0, constant = 1;
        List<Type> ptypes = dyn.getMethodSignature().getParameterTypes();
        for (char ch : recipe.getValue().toCharArray()) {
            if (ch == 1) {
                if (lit.length() > 0) { parts.add(new Term.Const(lit.toString())); lit.setLength(0); }
                if (arg >= dyn.getArgCount()) return new Term.Unknown("concat recipe");
                String pt = arg < ptypes.size() ? ptypes.get(arg).toString() : "java.lang.Object";
                parts.add(asText(term(dyn.getArg(arg), path, pos, depth + 1), pt));
                arg++;
            } else if (ch == 2) {
                if (constant >= dyn.getBootstrapArgCount() || !(dyn.getBootstrapArg(constant) instanceof StringConstant sc)) return new Term.Unknown("concat constant");
                lit.append(sc.getValue());
                constant++;
            } else {
                lit.append(ch);
            }
        }
        if (lit.length() > 0) parts.add(new Term.Const(lit.toString()));
        return new Term.Concat(parts);
    }

    /** The text a value of the given static type contributes to a concatenation. */
    private static Term asText(Term t, String type) {
        if (type.equals("char")) {
            if (t instanceof Term.Const c && c.value() instanceof Integer i) return new Term.Const(String.valueOf((char) (int) i));
            return new Term.Call("java.lang.String", "valueOf", List.of("char"), null, List.of(t), "java.lang.String");
        }
        if (type.equals("int") || type.equals("long") || type.equals("short") || type.equals("byte")) {
            if (t instanceof Term.Const c && c.value() instanceof Number n) return new Term.Const(String.valueOf(n.longValue()));
            return new Term.Call("java.lang.String", "valueOf", List.of(type.equals("long") ? "long" : "int"), null, List.of(t), "java.lang.String");
        }
        if (type.equals("boolean")) {
            if (t instanceof Term.Const c && c.value() instanceof Integer i) return new Term.Const(String.valueOf(i != 0));
            return new Term.Call("java.lang.String", "valueOf", List.of("boolean"), null, List.of(t), "java.lang.String");
        }
        if (type.equals("double") || type.equals("float") || type.endsWith("[]")) return new Term.Unknown("concatenated " + type);
        return t;
    }

    // ------------------------------------------------------------------ builders

    /** Where a builder came from: allocated on the path (defPos), or a parameter. */
    private record Root(String local, int defPos, Term.Param param) {}

    private Root rootOf(Local l, List<Stmt> path, int pos, int depth) {
        if (depth > MAX_DEPTH) return null;
        int d = lastDef(l, path, pos);
        if (d < 0) return null;
        Stmt def = path.get(d);
        if (def instanceof JIdentityStmt id) return id.getRightOp() instanceof JParameterRef pr && Term.BUILDERS.contains(pr.getType().toString()) ? new Root(null, d, new Term.Param(pr.getIndex(), pr.getType().toString())) : null;
        Value rhs = ((JAssignStmt) def).getRightOp();
        if (rhs instanceof JNewExpr ne) return Term.BUILDERS.contains(ne.getType().toString()) ? new Root(l.getName(), d, null) : null;
        if (rhs instanceof JCastExpr c && c.getOp() instanceof Local x) return rootOf(x, path, d, depth + 1);
        if (rhs instanceof Local x) return rootOf(x, path, d, depth + 1);
        // append and its relatives return the receiver
        if (rhs instanceof AbstractInstanceInvokeExpr ii && Term.BUILDERS.contains(ii.getMethodSignature().getDeclClassType().toString())
                && Term.BUILDERS.contains(ii.getMethodSignature().getType().toString())) return rootOf(ii.getBase(), path, d, depth + 1);
        return null;
    }

    /** The content of the builder held by l just before path[pos]: its initial content followed by the appends on the path. */
    private Term builderContent(Local l, List<Stmt> path, int pos, int depth) {
        Root root = rootOf(l, path, pos, depth);
        if (root == null) return new Term.Unknown("builder of unknown origin");
        List<Term> parts = new ArrayList<>();
        if (root.param() != null) parts.add(root.param());
        for (int i = root.defPos() + 1; i < pos; i++) {
            if (!(invokeOf(path.get(i)) instanceof AbstractInstanceInvokeExpr ii)) continue;
            var sig = ii.getMethodSignature();
            if (!Term.BUILDERS.contains(sig.getDeclClassType().toString())) continue;
            Root r = rootOf(ii.getBase(), path, i, depth + 1);
            if (r == null || !r.equals(root)) continue;
            String name = sig.getName();
            List<Type> pt = sig.getParameterTypes();
            switch (name) {
                case "<init>":
                    parts.clear();
                    if (pt.size() == 1 && !pt.get(0).toString().equals("int")) parts.add(term(ii.getArg(0), path, i, depth + 1));
                    break;
                case "append":
                    if (pt.size() != 1) return new Term.Unknown("append of a range");
                    parts.add(asText(term(ii.getArg(0), path, i, depth + 1), pt.get(0).toString()));
                    break;
                case "insert":
                    if (pt.size() == 2 && ii.getArg(0) instanceof IntConstant at && at.getValue() == 0) parts.add(0, asText(term(ii.getArg(1), path, i, depth + 1), pt.get(1).toString()));
                    else return new Term.Unknown("insert inside the content");
                    break;
                case "delete": case "deleteCharAt": case "replace": case "reverse": case "setLength": case "setCharAt": case "appendCodePoint":
                    return new Term.Unknown("builder " + name);
                default:
                    break; // observers
            }
        }
        return parts.size() == 1 ? parts.get(0) : new Term.Concat(parts);
    }

    static int lineOf(Stmt st) { return st.getPositionInfo().getStmtPosition().getFirstLine(); }
}
