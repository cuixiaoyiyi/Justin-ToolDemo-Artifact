package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.ArrayValue;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.StringValue;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.ArraySort;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BitVecSort;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.CharSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.RatNum;
import com.microsoft.z3.RealExpr;
import com.microsoft.z3.RealSort;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.SeqSort;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Sort;
import com.microsoft.z3.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.basic.Immediate;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.jimple.common.constant.DoubleConstant;
import sootup.core.jimple.common.constant.FloatConstant;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.LongConstant;
import sootup.core.jimple.common.constant.NullConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JAddExpr;
import sootup.core.jimple.common.expr.JAndExpr;
import sootup.core.jimple.common.expr.JCastExpr;
import sootup.core.jimple.common.expr.JCmpExpr;
import sootup.core.jimple.common.expr.JCmpgExpr;
import sootup.core.jimple.common.expr.JCmplExpr;
import sootup.core.jimple.common.expr.JDivExpr;
import sootup.core.jimple.common.expr.JEqExpr;
import sootup.core.jimple.common.expr.JGeExpr;
import sootup.core.jimple.common.expr.JGtExpr;
import sootup.core.jimple.common.expr.JInstanceOfExpr;
import sootup.core.jimple.common.expr.JLeExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.expr.JLtExpr;
import sootup.core.jimple.common.expr.JMulExpr;
import sootup.core.jimple.common.expr.JNeExpr;
import sootup.core.jimple.common.expr.JNegExpr;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JNewMultiArrayExpr;
import sootup.core.jimple.common.expr.JOrExpr;
import sootup.core.jimple.common.expr.JRemExpr;
import sootup.core.jimple.common.expr.JShlExpr;
import sootup.core.jimple.common.expr.JShrExpr;
import sootup.core.jimple.common.expr.JSubExpr;
import sootup.core.jimple.common.expr.JUshrExpr;
import sootup.core.jimple.common.expr.JXorExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JCaughtExceptionRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JStaticFieldRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JGotoStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.JNopStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JReturnVoidStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Intra-procedural symbolic execution of one Jimple body. Bounded DFS over the statement graph;
 * each feasible path yields a model that is turned into concrete argument values. Calls go through
 * a small chain: JDK summaries for String/Math/Objects/Character, everything else returns a fresh
 * symbol and marks the path imprecise (the CallHandler chain of plan section 7.1; inlining and
 * summaries of project methods are later steps).
 */
public final class SymbolicExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SymbolicExecutor.class);

    private final Context ctx;
    private final Solver solver;
    /** Null when the rewrite is switched off. */
    private final BridgeLift lift;

    /** An entry symbol: the value a field of the receiver has when the method is entered, with the constraints of its type. */
    private record Entry(SymValue value, List<BoolExpr> constraints) {}

    /** Entry symbols by chain of fields; the same symbol on every path, so that one receiver's values fit them all. */
    private final Map<String, Entry> entrySymbols = new java.util.LinkedHashMap<>();
    /** The concrete receivers the paths can be fitted to, or null. */
    private ReceiverStates receivers;
    private int receiverPaths, receiverFitted, receiverUnfitted, staticPaths;
    /** Chains of fields that no candidate receiver could satisfy, with the number of paths that needed them. */
    private final Map<String, Integer> unmetFields = new TreeMap<>();
    private final SymConfig cfg;
    private int fresh;
    /** Null when collections are left without a model. */
    private final CollectionSummaries collections;
    private ValueSource values = ValueSource.NONE;
    /** Null when the class of an object is left out of the path condition. */
    private final TypeModel types;
    /** Statements of a callee in another class above which the call is not followed. */
    private static final int MAX_FOREIGN_STATEMENTS = 60;
    /** Classes tried for a virtual call on an object whose class is open. */
    private static final int MAX_DISPATCH = 3;
    /** Whether the lines of a body count as lines of the method under test (same source file). */
    private final Map<Body, Boolean> ownLines = new java.util.IdentityHashMap<>();

    // counters
    private int explored, sat, unsat, solverTimeouts, loopHits, stepHits, constraintHits, pathBoundHits, solves, inlined;
    private boolean timeHit;
    private long solverNanos;
    private final Map<String, Integer> unmodeled = new TreeMap<>();

    // session: start() fills the work list, resume() spends time on it
    private MethodTarget method;
    private String methodSignature = "";
    /** Calls made on the receiver before the method itself, in this order; their arguments are symbols too. */
    private List<Prefix> prefix = List.of();
    private Body targetBody;
    private int prefixAborted;

    /**
     * A call made before the target, to bring an object into another state: a public method of the receiver's
     * class on the receiver (root ""), of an argument's class on that argument (root arg:i), or of the class of an
     * object the receiver is constructed from (root ctor:i); or, with root {@link #CONSTRUCT}, the constructor of
     * the receiver itself, whose arguments are then symbols like those of the method (the objects among them are
     * the ctor:i roots).
     */
    public record Prefix(MethodTarget method, Body body, String root) {
        public static final String CONSTRUCT = "new";
        public Prefix(MethodTarget method, Body body) { this(method, body, ""); }
        public boolean constructs() { return CONSTRUCT.equals(root); }
    }

    /** Roots of chains of fields other than the receiver: the arguments of the method and of the constructor. */
    public static final String ARG = "arg:", CTOR = "ctor:";

    /** The root object of a chain of fields: "", arg:i, ctor:i, or STATIC. */
    static String rootOf(String path) {
        if (path.startsWith(STATIC)) return STATIC;
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        return first.startsWith(ARG) || first.startsWith(CTOR) ? first : "";
    }

    /** The fields of a chain below its root object. */
    static List<String> segments(String path) {
        List<String> all = List.of(path.split("/"));
        return rootOf(path).isEmpty() || path.startsWith(STATIC) ? all : all.subList(1, all.size());
    }

    private static int indexOf(String root) { return Integer.parseInt(root.substring(root.indexOf(':') + 1)); }
    private Set<Integer> targets = Set.of();
    private Deque<SymState> work = new ArrayDeque<>();
    private final Map<String, Integer> paramIndexOfLocal = new java.util.HashMap<>();
    private final Set<Integer> reached = new java.util.HashSet<>();
    private boolean finished = true;
    private long usedMillis;
    private String stopReason;
    private int noGain;
    private java.util.function.Function<sootup.core.signatures.MethodSignature, java.util.Optional<Body>> calleeBodies = sig -> java.util.Optional.empty();

    /** Marker returned by invoke() when the call was inlined: the state now sits at the callee's first statement. */
    private static final SymValue INLINED = SymValue.object("<inlined>", null);

    public SymbolicExecutor(SymConfig cfg) {
        this.cfg = cfg;
        this.ctx = new Context(Map.of("model", "true"));
        this.solver = ctx.mkSolver();
        var p = ctx.mkParams();
        p.add("timeout", cfg.solverTimeoutMs());
        solver.setParameters(p);
        this.lift = cfg.bridgeLift() ? new BridgeLift(ctx) : null;
        this.collections = cfg.objects().collections() ? new CollectionSummaries(this, cfg.maxArrayLength()) : null;
        this.types = cfg.objects().types() ? new TypeModel(this, cfg) : null;
    }

    /** Who is a subtype of whom and which body a virtual call runs (see {@link TypeOracle}). */
    public void setTypeOracle(TypeOracle oracle) { if (types != null) types.setOracle(oracle); }

    int nextFresh() { return fresh++; }

    /** The string or number the model gives the contents of an object that was cast to String or a boxed type. */
    Value contentValue(Model model, SymValue view, String type) {
        return valueOf(model, null, view, TypeRef.ClassRef.of(type));
    }

    private static String outermost(String cls) {
        int i = cls.indexOf('$');
        return i < 0 ? cls : cls.substring(0, i);
    }

    private boolean ownLines(Body body) {
        if (method == null) return true;
        return ownLines.computeIfAbsent(body, b -> outermost(b.getMethodSignature().getDeclClassType().toString()).equals(outermost(method.declaringClass())));
    }

    private static boolean ofTheJdk(String cls) {
        return cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("com.sun.");
    }

    /** Where the values come from that a model does not describe (see {@link ValueSource}). */
    public void setValueSource(ValueSource source) { this.values = source == null ? ValueSource.NONE : source; }


    @Override public void close() { ctx.close(); }

    // ------------------------------------------------------------------ entry point

    /** Bodies of callees to inline (same-class calls only, see plan 7.1 strategy 4); default: none. */
    public void setCalleeResolver(java.util.function.Function<sootup.core.signatures.MethodSignature, java.util.Optional<Body>> resolver) {
        this.calleeBodies = resolver;
    }

    /**
     * One-shot use: start and spend the per-method budget.
     * @param targetLines source lines a directed search should try to reach first (may be empty)
     */
    public SymResult run(MethodTarget m, Body body, Set<Integer> targetLines) {
        start(m, body, targetLines);
        List<SymResult.PathInput> out = resume(cfg.methodTimeBudgetMs());
        return new SymResult(out, stats());
    }

    /** The candidate receivers of the method about to be explored (see {@link ReceiverStates}); null for none. */
    public void setReceiverStates(ReceiverStates states) { this.receivers = states; }

    /** Chains of fields (declaringClass#name, joined by '/') that paths needed and no candidate receiver provided. */
    public Map<String, Integer> unmetFields() { return new TreeMap<>(unmetFields); }

    /** Prepares the work list for m; nothing is explored until resume(). */
    public void start(MethodTarget m, Body body, Set<Integer> targetLines) {
        start(m, body, targetLines, List.of());
    }

    /**
     * Prepares the exploration of a sequence: the calls of before, one after the other on the same receiver, then
     * m. A path is a path through all of them; one that leaves a call of the prefix by an exception is dropped,
     * because the receiver it was to build does not exist.
     */
    public void start(MethodTarget m, Body body, Set<Integer> targetLines, List<Prefix> before) {
        method = m;
        methodSignature = body.getMethodSignature().toString();
        prefix = List.copyOf(before);
        targetBody = body;
        if (lift != null) lift.reset();
        entrySymbols.clear();
        targets = targetLines == null ? Set.of() : targetLines;
        work = new ArrayDeque<>();
        SymState s0 = new SymState(body, body.getStmts(), body.getStmts().isEmpty() ? null : body.getStmts().get(0));
        // the arguments of the method, and of the constructor when it is part of the sequence, exist from the start:
        // the calls before the method may act on them
        for (int i = 0; i < m.params().size(); i++) s0.params.put(i, paramSymbol(s0.pc, "p" + i, m.params().get(i).type(), ARG + i));
        for (Prefix p : prefix) {
            if (!p.constructs()) continue;
            for (int i = 0; i < p.method().params().size(); i++) s0.ctorParams.put(i, paramSymbol(s0.pc, "c" + i, p.method().params().get(i).type(), CTOR + i));
        }
        enterStage(s0, 0);
        if (s0.next != null) work.push(s0);
        finished = work.isEmpty();
        stopReason = null;
    }

    /** A parameter symbol; an object among them is a root of chains of fields, so that its state can be fitted and built. */
    private SymValue paramSymbol(List<BoolExpr> sink, String base, TypeRef t, String root) {
        SymValue v = symbolInto(sink, base, t, null);
        if (v.kind == SymValue.Kind.REF && v.hasFields && !v.isCollection()) return SymValue.entryObject(v.refType, v.nullFlag, root);
        return v;
    }

    /** The state moves to stage k of the sequence: a call of the prefix, the constructor, or the target. */
    private void enterStage(SymState s, int k) {
        s.stage = k;
        Body nb = k < prefix.size() ? prefix.get(k).body() : targetBody;
        java.util.HashMap<String, SymValue> kept = new java.util.HashMap<>();
        for (var e : s.locals.entrySet()) if (e.getKey().startsWith("static:")) kept.put(e.getKey(), e.getValue());
        s.locals = kept;
        s.visits.clear();
        s.body = nb;
        s.stmts = nb.getStmts();
        s.next = s.stmts.isEmpty() ? null : s.stmts.get(0);
        if (k < prefix.size() && prefix.get(k).constructs()) {
            // the receiver comes into being here: its fields are what the constructor writes, defaults otherwise
            s.self = SymValue.created(prefix.get(k).method().declaringClass());
            s.defaulted.add(s.self.id);
        }
    }

    /** True once the work list is empty or the method was cut for good (path bound, explosion). */
    public boolean finished() { return finished; }

    /** Why the last resume() stopped early, or null when it ran to the end of the work list. */
    public String stopReason() { return stopReason; }

    /**
     * Explores for up to budgetMs (wall clock) and returns the inputs of the satisfiable paths completed
     * in that time. Can be called again while !finished(); counters accumulate across calls.
     */
    public List<SymResult.PathInput> resume(long budgetMs) {
        long t0 = System.currentTimeMillis();
        long deadline = t0 + budgetMs;
        List<SymResult.PathInput> out = new ArrayList<>();
        stopReason = null;
        try {
            while (!work.isEmpty()) {
                if (System.currentTimeMillis() > deadline) { timeHit = true; stopReason = "time"; return out; }
                if (sat >= cfg.maxPathsPerMethod()) { pathBoundHits++; stopReason = "paths"; finished = true; return out; }
                if (exploding()) { finished = true; return out; }
                SymState s = work.pop();
                try {
                    // exception states forked by invoke/array reads arrive completed: never step them again
                    if (!s.completed) step(s);
                } catch (RuntimeException e) {
                    log.debug("path abandoned in {}: {}", method.id(), e.toString());
                    continue;
                }
                if (s.completed && s.stage < prefix.size()) { prefixAborted++; continue; }
                if (s.completed) {
                    explored++;
                    Map<String, Integer> chosen = new java.util.LinkedHashMap<>();
                    Model model = solveWithReceiver(s, chosen);
                    if (model == null) continue;
                    sat++;
                    boolean gain = false;
                    for (int l : s.lines) if (targets.contains(l) && reached.add(l)) gain = true;
                    noGain = gain ? 0 : noGain + 1;
                    out.add(extract(method, s, model, chosen));
                }
            }
            finished = true;
            return out;
        } finally {
            usedMillis += System.currentTimeMillis() - t0;
        }
    }

    /** Plan 7.3 explosion detection: many satisfiable paths without a new target, or a solver that mostly times out. */
    private boolean exploding() {
        if (!targets.isEmpty() && cfg.explosionNoGainPaths() > 0 && noGain >= cfg.explosionNoGainPaths()) { stopReason = "no-gain"; return true; }
        if (solverTimeouts > 5 && solverTimeouts * 2 > solves) { stopReason = "solver-timeouts"; return true; }
        return false;
    }

    public SymResult.Stats stats() {
        return new SymResult.Stats(explored + unsat + prefixAborted, sat, unsat, solverTimeouts, loopHits, stepHits, constraintHits, pathBoundHits,
                usedMillis, timeHit, new TreeMap<>(unmodeled), stopReason, reached.size(), inlined, finished,
                new SymResult.ReceiverUse(receiverPaths, receiverFitted, receiverUnfitted, staticPaths),
                new SymResult.SolverUse(solves, solverNanos / 1_000_000, lift == null ? 0 : lift.checksWithBridge(), lift == null ? 0 : lift.assertionsLifted(),
                        lift == null ? 0 : lift.variablesAsIntegers()));
    }

    // ------------------------------------------------------------------ interpreter

    /** Executes statements until the path forks, ends or is cut; forked successors go onto the work list. */
    private void step(SymState s) {
        MethodTarget m = method;
        Deque<SymState> work = this.work;
        Set<Integer> targets = this.targets;
        Map<String, Integer> paramLocals = paramIndexOfLocal;
        while (true) {
            Body body = s.body;
            List<Stmt> stmts = s.stmts;
            Stmt st = s.next;
            if (st == null) { s.completed = true; return; }
            if (++s.steps > cfg.maxStepsPerPath()) { stepHits++; return; }
            if (s.pc.size() > cfg.maxConstraintsPerPath()) { constraintHits++; return; }
            int line = st.getPositionInfo().getStmtPosition().getFirstLine();
            if (line > 0 && s.stage >= prefix.size() && (s.depth() == 0 || ownLines(body))) s.lines.add(line);   // the lines of the target, not of what built the receiver
            int visits = s.visits.merge(st, 1, Integer::sum);
            if (st.branches() && visits > cfg.maxLoopIterations() + 1) { loopHits++; return; }
            if (log.isTraceEnabled()) log.trace("  [{}] pc={} visits={} {}", System.identityHashCode(s), s.pc.size(), visits, st);

            if (st instanceof JIdentityStmt id) {
                identity(m, s, id, paramLocals);
                s.next = fallThrough(stmts, st);
            } else if (st instanceof JAssignStmt as) {
                Step r = assign(s, as, stmts, work);
                if (r == Step.STOP) return;
                if (r == Step.NEXT) s.next = fallThrough(stmts, st); // INLINED: already at the callee's first statement
            } else if (st instanceof JInvokeStmt inv) {
                SymValue r = invoke(s, inv.getInvokeExpr().orElse(null), null, stmts, work);
                if (s.completed) return;
                if (r != INLINED) s.next = fallThrough(stmts, st);
            } else if (st instanceof JIfStmt ifs) {
                BoolExpr cond = condition(s, ifs.getCondition());
                Stmt target = body.getBranchTargetsOf(ifs).get(0);
                Stmt fall = fallThrough(stmts, st);
                forkTwo(s, cond, target, fall, work, targets);
                return;
            } else if (st instanceof JGotoStmt g) {
                s.next = body.getBranchTargetsOf(g).get(0);
            } else if (st instanceof JSwitchStmt sw) {
                switchStmt(s, sw, body, work, targets);
                return;
            } else if (st instanceof JReturnStmt || st instanceof JReturnVoidStmt) {
                if (s.depth() > 0) {
                    // return from an inlined callee: restore the caller's locals and continue after the call site
                    SymValue rv = st instanceof JReturnStmt rs ? eval(s, rs.getOp()) : null;
                    SymState.Frame f = s.frames.pop();
                    s.locals = new java.util.HashMap<>(f.savedLocals());
                    s.body = f.body();
                    s.stmts = f.stmts();
                    s.pendingArgs = null;
                    s.pendingThis = null;
                    if (f.resultLocal() != null) s.set(f.resultLocal(), rv == null ? SymValue.unknown() : rv);
                    s.next = fallThrough(f.stmts(), f.callSite());
                    continue;
                }
                if (s.stage < prefix.size()) {
                    // a call of the prefix returned: on to the next one, or to the target, with the heap as it is now
                    enterStage(s, s.stage + 1);
                    if (s.next == null) { s.completed = true; return; }
                    continue;
                }
                s.completed = true;
                return;
            } else if (st instanceof JThrowStmt th) {
                SymValue v = eval(s, th.getOp());
                raise(s, v != null && v.refType != null ? v.refType : "java.lang.Throwable");
                if (s.completed) return;
                continue;
            } else if (st instanceof JNopStmt) {
                s.next = fallThrough(stmts, st);
            } else {
                // monitor statements and anything exotic: skip
                s.next = fallThrough(stmts, st);
            }
        }
    }

    /** The statement after st, for the summaries that fork a path at a call. */
    Stmt after(List<Stmt> stmts, Stmt st) { return fallThrough(stmts, st); }

    /** A string taken out of a collection whose elements had no type yet: the collection holds strings. */
    SymValue asString(SymState s, SymValue v) {
        return collections != null && v != null && v.link != null ? collections.cast(s, v, "java.lang.String") : v;
    }

    /**
     * The path throws an exception of the type at its current statement. A handler of the body that covers the
     * statement and takes the type continues the path at the handler; without one the callee is left and the
     * caller's handlers are tried at the call site; with none at all the path ends with the exception.
     */
    void raise(SymState s, String type) {
        s.thrown = type;
        s.caught = null;
        while (true) {
            Stmt at = s.next;
            Stmt handler = null;
            if (at != null) {
                Map<sootup.core.types.ClassType, Stmt> handlers;
                try {
                    handlers = s.body.getStmtGraph().exceptionalSuccessors(at);
                } catch (RuntimeException e) {
                    handlers = Map.of();
                }
                for (var e : handlers.entrySet()) {
                    if (catches(type, e.getKey().toString())) { handler = e.getValue(); break; }
                }
            }
            if (handler != null) {
                s.thrown = null;
                s.caught = type;
                s.pendingArgs = null;
                s.pendingThis = null;
                s.next = handler;
                return;
            }
            if (s.depth() == 0) { s.completed = true; return; }
            // out of the callee, to the caller's handlers around the call site
            SymState.Frame f = s.frames.pop();
            s.locals = new java.util.HashMap<>(f.savedLocals());
            s.body = f.body();
            s.stmts = f.stmts();
            s.next = f.callSite();
            s.pendingArgs = null;
            s.pendingThis = null;
        }
    }

    /** Whether a handler for caught takes an exception of type thrown; when the classes are not known, only a catch-all does. */
    private boolean catches(String thrown, String caught) {
        if (thrown.equals(caught) || caught.equals("java.lang.Throwable") || caught.equals("java.lang.Exception") && !thrown.endsWith("Error")) return true;
        Boolean is = (types != null ? types.oracle() : TypeOracle.JDK).subtype(thrown, caught);
        return Boolean.TRUE.equals(is);
    }

    private static Stmt fallThrough(List<Stmt> stmts, Stmt st) {
        int i = stmts.indexOf(st);
        return i >= 0 && i + 1 < stmts.size() ? stmts.get(i + 1) : null;
    }

    private void identity(MethodTarget m, SymState s, JIdentityStmt id, Map<String, Integer> paramLocals) {
        String name = id.getLeftOp().toString();
        if (id.getRightOp() instanceof JParameterRef pr) {
            int i = pr.getIndex();
            if (s.depth() > 0) {
                SymValue a = s.pendingArgs != null && i < s.pendingArgs.size() ? s.pendingArgs.get(i) : null;
                s.set(name, a == null ? SymValue.unknown() : a);
                return;
            }
            if (s.stage < prefix.size()) {
                Prefix p = prefix.get(s.stage);
                if (p.constructs()) {
                    SymValue c = s.ctorParams.get(i);
                    s.set(name, c == null ? SymValue.unknown() : c);
                    return;
                }
                MethodTarget pm = p.method();
                SymValue sym = symbolFor(s, "q" + s.stage + "_p" + i, i < pm.params().size() ? pm.params().get(i).type() : TypeRef.OBJECT);
                s.set(name, sym);
                s.prefixParams.put(s.stage * 256 + i, sym);
                return;
            }
            paramLocals.put(name, i);
            SymValue sym = s.params.get(i);
            if (sym == null) {
                sym = symbolFor(s, "p" + i, i < m.params().size() ? m.params().get(i).type() : TypeRef.OBJECT);
                s.params.put(i, sym);
            }
            s.set(name, sym);
        } else if (id.getRightOp() instanceof JThisRef) {
            if (s.depth() > 0) {
                s.set(name, s.pendingThis == null ? SymValue.object(m.declaringClass(), null) : s.pendingThis);
                return;
            }
            // whose method this stage is: an argument's, an object's the receiver is built from, or the receiver's
            String root = s.stage < prefix.size() ? prefix.get(s.stage).root() : "";
            if (root.startsWith(ARG)) { SymValue a = s.params.get(indexOf(root)); s.set(name, a == null ? SymValue.unknown() : a); return; }
            if (root.startsWith(CTOR)) { SymValue a = s.ctorParams.get(indexOf(root)); s.set(name, a == null ? SymValue.unknown() : a); return; }
            // one receiver for the calls of the prefix and the target
            if (s.self == null) s.self = SymValue.entryObject(m.declaringClass(), null, "");
            s.set(name, s.self);
        } else if (id.getRightOp() instanceof JCaughtExceptionRef) {
            // the exception a handler starts with: of the class that was thrown, when the path threw it
            s.set(name, s.caught != null ? SymValue.created(s.caught) : SymValue.object("java.lang.Throwable", null));
            s.caught = null;
        }
    }

    /** Fresh symbol of the right sort for a parameter type, with the type's range constraints. */
    private SymValue symbolFor(SymState s, String base, TypeRef t) {
        return symbolInto(s.pc, base, t, null);
    }

    /**
     * A symbol of the sort of t; the constraints of the type go to sink. With entryPath the symbol stands for
     * part of the receiver as it is on entry: no artificial bound on the length of a string or an array (the
     * concrete receivers decide), and objects and arrays remember the chain of fields that leads to them.
     */
    private SymValue symbolInto(List<BoolExpr> sink, String base, TypeRef t, String entryPath) {
        String e = t.erasureName();
        boolean entry = entryPath != null;
        switch (e) {
            case "int": return SymValue.bv32(ctx.mkBVConst(base, 32));
            case "boolean": { BitVecExpr b = ctx.mkBVConst(base, 32); sink.add(ctx.mkOr(ctx.mkEq(b, bv32(0)), ctx.mkEq(b, bv32(1)))); return SymValue.bv32(b); }
            case "char": { BitVecExpr b = ctx.mkBVConst(base, 32); sink.add(ctx.mkBVSGE(b, bv32(0))); sink.add(ctx.mkBVSLE(b, bv32(0xFFFF))); return SymValue.bv32(b); }
            case "byte": { BitVecExpr b = ctx.mkBVConst(base, 32); sink.add(ctx.mkBVSGE(b, bv32(-128))); sink.add(ctx.mkBVSLE(b, bv32(127))); return SymValue.bv32(b); }
            case "short": { BitVecExpr b = ctx.mkBVConst(base, 32); sink.add(ctx.mkBVSGE(b, bv32(-32768))); sink.add(ctx.mkBVSLE(b, bv32(32767))); return SymValue.bv32(b); }
            case "long": return SymValue.bv64(ctx.mkBVConst(base, 64));
            case "float": case "double": return SymValue.real((RealExpr) ctx.mkConst(base, ctx.getRealSort()));
            case "java.lang.String": case "java.lang.CharSequence": {
                Expr<SeqSort<CharSort>> str = ctx.mkConst(base + "_s", ctx.getStringSort());
                if (!entry) sink.add(ctx.mkLe(ctx.mkLength(str), ctx.mkInt(cfg.maxStringLength())));
                return SymValue.str(str, ctx.mkBoolConst(base + "_null"));
            }
            default: break;
        }
        if (e.endsWith("[]")) {
            String elem = e.substring(0, e.length() - 2);
            Sort range = sortOf(elem);
            if (range != null && !elem.endsWith("[]")) {
                ArrayExpr<BitVecSort, Sort> data = ctx.mkArrayConst(base + "_a", ctx.mkBitVecSort(32), range);
                BitVecExpr len = ctx.mkBVConst(base + "_len", 32);
                sink.add(ctx.mkBVSGE(len, bv32(0)));
                if (!entry) sink.add(ctx.mkBVSLE(len, bv32(cfg.maxArrayLength())));
                SymValue.ArrayInfo info = new SymValue.ArrayInfo(data, len, kindOf(elem), elem);
                return entry ? SymValue.entryArray(info, ctx.mkBoolConst(base + "_null"), e, entryPath) : SymValue.array(info, ctx.mkBoolConst(base + "_null"), e);
            }
        }
        if (collections != null && CollectionSummaries.shapeOf(e) != null) {
            return collections.symbol(sink, base, e, entry ? t : values.concrete(t), ctx.mkBoolConst(base + "_null"), entryPath);
        }
        // boxed primitives behave like their primitive (nullness ignored)
        TypeRef.Kind boxed = boxedKind(e);
        if (boxed != null) return symbolInto(sink, base, new TypeRef.Primitive(boxed), entryPath);
        return entry ? SymValue.entryObject(e, ctx.mkBoolConst(base + "_null"), entryPath) : SymValue.object(e, ctx.mkBoolConst(base + "_null"));
    }

    /** First segment of a chain that starts at a static field instead of the receiver. */
    static final String STATIC = "static:";

    /** The entry symbol of a chain of fields, the same on every path; the path records that it depends on it. */
    private SymValue entry(SymState s, String path, String name, TypeRef type) {
        Entry e = entrySymbols.get(path);
        if (e == null) {
            List<BoolExpr> cs = new ArrayList<>();
            SymValue sym = symbolInto(cs, "e" + entrySymbols.size() + "_" + name, type, path);
            e = new Entry(sym, List.copyOf(cs));
            entrySymbols.put(path, e);
        }
        if (s.entries.add(path)) s.pc.addAll(e.constraints());
        return e.value();
    }

    /** declaringClass#name: what a field is called in the heap and in the chains handed to {@link ReceiverStates}. */
    private static String fieldKey(JInstanceFieldRef fr) {
        return fr.getFieldSignature().getDeclClassType() + "#" + fr.getFieldSignature().getName();
    }

    /** The value of a field on this path: what the path wrote, else the entry value, else a fresh unknown. */
    private SymValue readField(SymState s, SymValue object, JInstanceFieldRef fr) {
        String key = fieldKey(fr);
        SymValue cur = s.field(object, key);
        if (cur != null) return cur;
        TypeRef type = typeRefOf(fr.getFieldSignature().getType().toString());
        SymValue v;
        if (object.entryPath != null) {
            v = entry(s, object.entryPath.isEmpty() ? key : object.entryPath + "/" + key, fr.getFieldSignature().getName(), type);
        } else if (s.defaulted.contains(object.id)) {
            v = defaultValue(type);
        } else {
            v = symbolFor(s, "f" + (fresh++) + "_" + fr.getFieldSignature().getName(), type);
        }
        s.putField(object, key, v);
        return v;
    }

    /** What a field holds before anything is written to it. */
    private SymValue defaultValue(TypeRef t) {
        String e = t.erasureName();
        switch (e) {
            case "int": case "short": case "byte": case "char": case "boolean": return SymValue.bv32(bv32(0));
            case "long": return SymValue.bv64(ctx.mkBV(0, 64));
            case "float": case "double": return realConst(0);
            default: return SymValue.nullRef(e);
        }
    }

    private static TypeRef.Kind boxedKind(String e) {
        for (TypeRef.Kind k : TypeRef.Kind.values()) if (k.boxed.equals(e) && k != TypeRef.Kind.VOID) return k;
        return null;
    }

    private Sort sortOf(String elem) {
        return switch (elem) {
            case "int", "short", "byte", "char", "boolean" -> ctx.mkBitVecSort(32);
            case "long" -> ctx.mkBitVecSort(64);
            case "float", "double" -> ctx.getRealSort();
            case "java.lang.String", "java.lang.CharSequence" -> ctx.getStringSort();
            default -> null;
        };
    }

    private static SymValue.Kind kindOf(String elem) {
        return switch (elem) {
            case "long" -> SymValue.Kind.BV64;
            case "float", "double" -> SymValue.Kind.REAL;
            case "java.lang.String", "java.lang.CharSequence" -> SymValue.Kind.STR;
            default -> SymValue.Kind.BV32;
        };
    }

    // ---------- assignments ----------

    /** @return false when the statement forked (array/null checks) and the current state must not continue */
    /** Outcome of interpreting an assignment: advance, drop the state (forked/completed), or continue inside an inlined callee. */
    private enum Step { NEXT, STOP, INLINED }

    private Step assign(SymState s, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        sootup.core.jimple.basic.Value rhs = as.getRightOp();
        SymValue v;
        if (rhs instanceof AbstractInvokeExpr inv) {
            v = invoke(s, inv, as, stmts, work);
            if (s.completed) return Step.STOP;
            if (v == INLINED) return Step.INLINED;
            if (v == null) v = SymValue.unknown();
        } else if (rhs instanceof JArrayRef ar) {
            v = arrayRead(s, ar, stmts, work, as);
            if (v == null) return Step.STOP;
        } else {
            v = eval(s, rhs);
        }
        var lhs = as.getLeftOp();
        if (lhs instanceof Local l) {
            s.set(l.getName(), v);
        } else if (lhs instanceof JArrayRef ar) {
            SymValue base = eval(s, ar.getBase());
            if (base != null && base.isArray() && v != null && v.expr != null && ar.getIndex() != null) {
                SymValue idx = eval(s, ar.getIndex());
                if (idx != null && idx.kind == SymValue.Kind.BV32 && v.kind == base.array.elem) {
                    @SuppressWarnings("unchecked") ArrayExpr<BitVecSort, Sort> data = (ArrayExpr<BitVecSort, Sort>) s.contents(base).data;
                    @SuppressWarnings("unchecked") Expr<Sort> val = (Expr<Sort>) v.expr;
                    s.arrays.put(base.id, ctx.mkStore(data, idx.bv(), val));
                }
            }
        } else if (lhs instanceof JInstanceFieldRef fr) {
            SymValue base = eval(s, fr.getBase());
            if (base != null && base.hasFields && v != null) s.putField(base, fieldKey(fr), v);
        } else if (lhs instanceof JStaticFieldRef sf) {
            s.set("static:" + sf.getFieldSignature(), v);
        }
        return Step.NEXT;
    }

    /** Symbolic array read with null and bounds forks; returns null when the path forked. */
    private SymValue arrayRead(SymState s, JArrayRef ar, List<Stmt> stmts, Deque<SymState> work, Stmt at) {
        SymValue base = eval(s, ar.getBase());
        SymValue idx = eval(s, ar.getIndex());
        if (base == null || !base.isArray() || idx == null || idx.kind != SymValue.Kind.BV32) {
            note(s, "array read on unmodeled base");
            return fresh(s, base != null && base.array != null ? base.array.elem : SymValue.Kind.BV32);
        }
        // null base -> NullPointerException path
        if (base.nullFlag != null) {
            SymState npe = s.copy();
            npe.pc.add(base.nullFlag);
            raise(npe, "java.lang.NullPointerException");
            work.push(npe);
            s.pc.add(ctx.mkNot(base.nullFlag));
        }
        BoolExpr inBounds = ctx.mkAnd(ctx.mkBVSGE(idx.bv(), bv32(0)), ctx.mkBVSLT(idx.bv(), base.array.length));
        SymState oob = s.copy();
        oob.pc.add(ctx.mkNot(inBounds));
        raise(oob, "java.lang.ArrayIndexOutOfBoundsException");
        work.push(oob);
        s.pc.add(inBounds);
        @SuppressWarnings("unchecked") ArrayExpr<BitVecSort, Sort> data = (ArrayExpr<BitVecSort, Sort>) s.contents(base).data;
        Expr<?> sel = ctx.mkSelect(data, idx.bv());
        return switch (base.array.elem) {
            case BV64 -> SymValue.bv64(sel);
            case REAL -> SymValue.real(sel);
            case STR -> SymValue.str(sel, null);
            default -> SymValue.bv32(sel);
        };
    }

    // ---------- expressions ----------

    private SymValue eval(SymState s, sootup.core.jimple.basic.Value v) {
        if (v instanceof Local l) {
            SymValue sv = s.local(l.getName());
            if (sv == null) { sv = SymValue.unknown(); s.set(l.getName(), sv); }
            return sv;
        }
        if (v instanceof IntConstant c) return SymValue.bv32(bv32(c.getValue()));
        if (v instanceof LongConstant c) return SymValue.bv64(ctx.mkBV(c.getValue(), 64));
        if (v instanceof DoubleConstant c) return realConst(c.getValue());
        if (v instanceof FloatConstant c) return realConst(c.getValue());
        if (v instanceof StringConstant c) return SymValue.str(ctx.mkString(c.getValue()), null);
        if (v instanceof NullConstant) return SymValue.nullRef(null);
        if (v instanceof ClassConstant) return SymValue.object("java.lang.Class", null);
        if (v instanceof JNewExpr ne) {
            String made = ne.getType().toString();
            return collections != null && CollectionSummaries.shapeOf(made) != null ? collections.created(made) : SymValue.created(made);
        }
        if (v instanceof JNewArrayExpr na) {
            SymValue size = eval(s, na.getSize());
            String elem = na.getBaseType().toString();
            Sort range = sortOf(elem);
            if (range == null || size == null || size.kind != SymValue.Kind.BV32) return SymValue.object(elem + "[]", null);
            ArrayExpr<BitVecSort, Sort> data = ctx.mkArrayConst("arr" + (fresh++), ctx.mkBitVecSort(32), range);
            // negative size -> NegativeArraySizeException is not forked here; the constraint keeps the common path
            s.pc.add(ctx.mkBVSGE(size.bv(), bv32(0)));
            return SymValue.array(new SymValue.ArrayInfo(data, size.bv(), kindOf(elem), elem), null, elem + "[]");
        }
        if (v instanceof JNewMultiArrayExpr) return SymValue.object("array", null);
        if (v instanceof JLengthExpr le) {
            SymValue base = eval(s, le.getOp());
            if (base != null && base.isArray()) {
                if (base.nullFlag != null) s.pc.add(ctx.mkNot(base.nullFlag));
                return SymValue.bv32(base.array.length);
            }
            note(s, "length of unmodeled array");
            return fresh(s, SymValue.Kind.BV32);
        }
        if (v instanceof JInstanceFieldRef fr) {
            SymValue base = eval(s, fr.getBase());
            if (base != null && base.hasFields) return readField(s, base, fr);
            note(s, "field read on unmodeled base");
            return fresh(s, kindOf(fr.getFieldSignature().getType().toString()));
        }
        if (v instanceof JStaticFieldRef sf) {
            SymValue sv = s.local("static:" + sf.getFieldSignature());
            if (sv == null) {
                // what the path did not write is the value the class starts with: an entry symbol, like a field of the receiver
                String path = STATIC + sf.getFieldSignature().getDeclClassType() + "#" + sf.getFieldSignature().getName();
                sv = entry(s, path, sf.getFieldSignature().getName(), typeRefOf(sf.getFieldSignature().getType().toString()));
                s.set("static:" + sf.getFieldSignature(), sv);
            }
            return sv;
        }
        if (v instanceof JCastExpr ce) return cast(s, eval(s, ce.getOp()), ce.getType().toString());
        if (v instanceof JInstanceOfExpr io) {
            if (types == null) { note(s, "instanceof"); return fresh(s, SymValue.Kind.BV32); }
            return SymValue.bv32(ctx.mkITE(types.instanceOf(s, eval(s, io.getOp()), io.getCheckType().toString()), bv32(1), bv32(0)));
        }
        if (v instanceof JNegExpr ne) {
            SymValue o = eval(s, ne.getOp());
            if (o.kind == SymValue.Kind.BV32 || o.kind == SymValue.Kind.BV64) return o.kind == SymValue.Kind.BV32 ? SymValue.bv32(ctx.mkBVNeg(o.bv())) : SymValue.bv64(ctx.mkBVNeg(o.bv()));
            if (o.kind == SymValue.Kind.REAL) return SymValue.real(ctx.mkUnaryMinus((ArithExpr<RealSort>) o.expr));
            return fresh(s, SymValue.Kind.BV32);
        }
        if (v instanceof AbstractBinopExpr b) return binop(s, b);
        if (v instanceof AbstractInvokeExpr inv) return null; // handled by the caller
        note(s, "expression " + v.getClass().getSimpleName());
        return SymValue.unknown();
    }

    private SymValue realConst(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return SymValue.real((RealExpr) ctx.mkConst("special" + (fresh++), ctx.getRealSort()));
        return SymValue.real(ctx.mkReal(java.math.BigDecimal.valueOf(d).toPlainString()));
    }

    private static TypeRef typeRefOf(String jimpleType) {
        TypeRef.Kind k = TypeRef.Kind.fromKeyword(jimpleType);
        if (k != null) return new TypeRef.Primitive(k);
        int dims = 0;
        String t = jimpleType;
        while (t.endsWith("[]")) { dims++; t = t.substring(0, t.length() - 2); }
        TypeRef base = TypeRef.Kind.fromKeyword(t) != null ? new TypeRef.Primitive(TypeRef.Kind.fromKeyword(t)) : TypeRef.ClassRef.of(t);
        for (int i = 0; i < dims; i++) base = new TypeRef.ArrayRef(base);
        return base;
    }

    @SuppressWarnings("unchecked")
    private SymValue binop(SymState s, AbstractBinopExpr b) {
        SymValue x = eval(s, b.getOp1()), y = eval(s, b.getOp2());
        if (x == null || y == null) return fresh(s, SymValue.Kind.BV32);
        // comparisons yielding int (cmp/cmpl/cmpg) or booleans-as-int (eq/ne/... appear in ifs, but also as values)
        if (b instanceof JCmpExpr || b instanceof JCmplExpr || b instanceof JCmpgExpr) {
            BoolExpr lt, eq;
            if (x.isNumeric() && y.isNumeric() && sameKind(x, y)) {
                if (x.kind == SymValue.Kind.REAL) { lt = ctx.mkLt((ArithExpr<RealSort>) x.expr, (ArithExpr<RealSort>) y.expr); eq = ctx.mkEq(x.expr, y.expr); }
                else { lt = ctx.mkBVSLT(x.bv(), y.bv()); eq = ctx.mkEq(x.expr, y.expr); }
                return SymValue.bv32(ctx.mkITE(lt, bv32(-1), ctx.mkITE(eq, bv32(0), bv32(1))));
            }
            return fresh(s, SymValue.Kind.BV32);
        }
        if (b instanceof JEqExpr || b instanceof JNeExpr || b instanceof JLtExpr || b instanceof JLeExpr || b instanceof JGtExpr || b instanceof JGeExpr) {
            BoolExpr c = condition(s, b);
            return SymValue.bv32(ctx.mkITE(c, bv32(1), bv32(0)));
        }
        if (x.kind == SymValue.Kind.REAL || y.kind == SymValue.Kind.REAL) {
            if (!(x.kind == SymValue.Kind.REAL && y.kind == SymValue.Kind.REAL)) return fresh(s, SymValue.Kind.REAL);
            ArithExpr<RealSort> a = (ArithExpr<RealSort>) x.expr, c = (ArithExpr<RealSort>) y.expr;
            if (b instanceof JAddExpr) return SymValue.real(ctx.mkAdd(a, c));
            if (b instanceof JSubExpr) return SymValue.real(ctx.mkSub(a, c));
            if (b instanceof JMulExpr) return SymValue.real(ctx.mkMul(a, c));
            if (b instanceof JDivExpr) return SymValue.real(ctx.mkDiv(a, c));
            return fresh(s, SymValue.Kind.REAL);
        }
        if (!(x.kind == SymValue.Kind.BV32 || x.kind == SymValue.Kind.BV64) || !(y.kind == SymValue.Kind.BV32 || y.kind == SymValue.Kind.BV64)) {
            return fresh(s, SymValue.Kind.BV32);
        }
        boolean wide = x.kind == SymValue.Kind.BV64 || y.kind == SymValue.Kind.BV64;
        BitVecExpr a = x.bv(), c = y.bv();
        if (b instanceof JShlExpr || b instanceof JShrExpr || b instanceof JUshrExpr) {
            // shift amount is an int; widen it to the value's width and mask like the JVM does
            int width = x.kind == SymValue.Kind.BV64 ? 64 : 32;
            BitVecExpr amt = c;
            if (y.kind == SymValue.Kind.BV64 && width == 32) amt = ctx.mkExtract(31, 0, c);
            if (y.kind == SymValue.Kind.BV32 && width == 64) amt = ctx.mkZeroExt(32, c);
            amt = ctx.mkBVAND(amt, ctx.mkBV(width - 1, width));
            BitVecExpr r = b instanceof JShlExpr ? ctx.mkBVSHL(a, amt) : b instanceof JShrExpr ? ctx.mkBVASHR(a, amt) : ctx.mkBVLSHR(a, amt);
            return width == 64 ? SymValue.bv64(r) : SymValue.bv32(r);
        }
        if (wide) { if (x.kind == SymValue.Kind.BV32) a = ctx.mkSignExt(32, a); if (y.kind == SymValue.Kind.BV32) c = ctx.mkSignExt(32, c); }
        BitVecExpr r;
        if (b instanceof JAddExpr) r = ctx.mkBVAdd(a, c);
        else if (b instanceof JSubExpr) r = ctx.mkBVSub(a, c);
        else if (b instanceof JMulExpr) r = ctx.mkBVMul(a, c);
        else if (b instanceof JDivExpr) { s.pc.add(ctx.mkNot(ctx.mkEq(c, ctx.mkBV(0, wide ? 64 : 32)))); r = ctx.mkBVSDiv(a, c); }
        else if (b instanceof JRemExpr) { s.pc.add(ctx.mkNot(ctx.mkEq(c, ctx.mkBV(0, wide ? 64 : 32)))); r = ctx.mkBVSRem(a, c); }
        else if (b instanceof JAndExpr) r = ctx.mkBVAND(a, c);
        else if (b instanceof JOrExpr) r = ctx.mkBVOR(a, c);
        else if (b instanceof JXorExpr) r = ctx.mkBVXOR(a, c);
        else return fresh(s, wide ? SymValue.Kind.BV64 : SymValue.Kind.BV32);
        return wide ? SymValue.bv64(r) : SymValue.bv32(r);
    }

    private static boolean sameKind(SymValue a, SymValue b) { return a.kind == b.kind; }

    @SuppressWarnings("unchecked")
    private SymValue cast(SymState s, SymValue v, String to) {
        if (v == null) return SymValue.unknown();
        switch (to) {
            case "int": case "short": case "byte": case "char": {
                BitVecExpr b;
                if (v.kind == SymValue.Kind.BV32) b = v.bv();
                else if (v.kind == SymValue.Kind.BV64) b = ctx.mkExtract(31, 0, v.bv());
                else if (v.kind == SymValue.Kind.REAL) b = ctx.mkInt2BV(32, ctx.mkReal2Int((Expr<RealSort>) v.expr));
                else return fresh(s, SymValue.Kind.BV32);
                if (to.equals("short")) b = ctx.mkSignExt(16, ctx.mkExtract(15, 0, b));
                if (to.equals("byte")) b = ctx.mkSignExt(24, ctx.mkExtract(7, 0, b));
                if (to.equals("char")) b = ctx.mkZeroExt(16, ctx.mkExtract(15, 0, b));
                return SymValue.bv32(b);
            }
            case "long": {
                if (v.kind == SymValue.Kind.BV64) return v;
                if (v.kind == SymValue.Kind.BV32) return SymValue.bv64(ctx.mkSignExt(32, v.bv()));
                if (v.kind == SymValue.Kind.REAL) return SymValue.bv64(ctx.mkInt2BV(64, ctx.mkReal2Int((Expr<RealSort>) v.expr)));
                return fresh(s, SymValue.Kind.BV64);
            }
            case "float": case "double": {
                if (v.kind == SymValue.Kind.REAL) return v;
                if (v.kind == SymValue.Kind.BV32 || v.kind == SymValue.Kind.BV64) return SymValue.real(ctx.mkInt2Real(ctx.mkBV2Int(v.bv(), true)));
                return fresh(s, SymValue.Kind.REAL);
            }
            default:
                if (collections != null && v.link != null) return collections.cast(s, v, to);
                // reference casts: the path on which the cast fails is not followed
                return types != null ? types.cast(s, v, to) : v;
        }
    }

    // ---------- conditions ----------

    @SuppressWarnings("unchecked")
    private BoolExpr condition(SymState s, sootup.core.jimple.basic.Value c) {
        if (!(c instanceof AbstractBinopExpr b)) return ctx.mkBoolConst("cond" + (fresh++));
        SymValue x = eval(s, b.getOp1()), y = eval(s, b.getOp2());
        boolean eq = b instanceof JEqExpr, ne = b instanceof JNeExpr;
        // null comparisons
        if ((eq || ne) && (isRefLike(x) || isRefLike(y))) {
            BoolExpr same;
            if (x.isDefinitelyNull() && y.isDefinitelyNull()) same = ctx.mkTrue();
            else if (x.isDefinitelyNull()) same = nullOf(y);
            else if (y.isDefinitelyNull()) same = nullOf(x);
            else if (x.kind == SymValue.Kind.STR && y.kind == SymValue.Kind.STR && x.expr != null && y.expr != null) same = ctx.mkEq(x.expr, y.expr); // approximates identity by equality
            else { note(s, "reference identity comparison"); same = ctx.mkBoolConst("same" + (fresh++)); }
            return eq ? same : ctx.mkNot(same);
        }
        if (x.isNumeric() && y.isNumeric()) {
            if (x.kind == SymValue.Kind.REAL || y.kind == SymValue.Kind.REAL) {
                if (x.kind != y.kind) return ctx.mkBoolConst("cond" + (fresh++));
                ArithExpr<RealSort> a = (ArithExpr<RealSort>) x.expr, d = (ArithExpr<RealSort>) y.expr;
                if (eq) return ctx.mkEq(a, d); if (ne) return ctx.mkNot(ctx.mkEq(a, d));
                if (b instanceof JLtExpr) return ctx.mkLt(a, d); if (b instanceof JLeExpr) return ctx.mkLe(a, d);
                if (b instanceof JGtExpr) return ctx.mkGt(a, d); return ctx.mkGe(a, d);
            }
            BitVecExpr a = x.bv(), d = y.bv();
            if (x.kind != y.kind) { if (x.kind == SymValue.Kind.BV32) a = ctx.mkSignExt(32, a); else d = ctx.mkSignExt(32, d); }
            if (eq) return ctx.mkEq(a, d); if (ne) return ctx.mkNot(ctx.mkEq(a, d));
            if (b instanceof JLtExpr) return ctx.mkBVSLT(a, d); if (b instanceof JLeExpr) return ctx.mkBVSLE(a, d);
            if (b instanceof JGtExpr) return ctx.mkBVSGT(a, d); return ctx.mkBVSGE(a, d);
        }
        note(s, "unmodeled condition");
        return ctx.mkBoolConst("cond" + (fresh++));
    }

    private static boolean isRefLike(SymValue v) { return v.kind == SymValue.Kind.REF || v.kind == SymValue.Kind.STR || v.isDefinitelyNull(); }

    private BoolExpr nullOf(SymValue v) {
        if (v.isDefinitelyNull()) return ctx.mkTrue();
        if (v.nullFlag != null) return v.nullFlag;
        if (v.kind == SymValue.Kind.REF || v.kind == SymValue.Kind.STR) return ctx.mkFalse();
        return ctx.mkBoolConst("null" + (fresh++));
    }

    private void forkTwo(SymState s, BoolExpr cond, Stmt target, Stmt fall, Deque<SymState> work, Set<Integer> targets) {
        SymState taken = s.copy();
        taken.pc.add(cond);
        taken.next = target;
        SymState skipped = s;
        skipped.pc.add(ctx.mkNot(cond));
        skipped.next = fall;
        boolean takenFeasible = feasible(taken), skippedFeasible = feasible(skipped);
        // DFS: the state pushed last is explored first; prefer the branch that leads to a target line
        boolean preferTaken = target != null && targets.contains(lineOf(target)) && !(fall != null && targets.contains(lineOf(fall)));
        if (preferTaken) { if (skippedFeasible) work.push(skipped); if (takenFeasible) work.push(taken); }
        else { if (takenFeasible) work.push(taken); if (skippedFeasible) work.push(skipped); }
        if (!takenFeasible) unsat++;
        if (!skippedFeasible) unsat++;
    }

    private void switchStmt(SymState s, JSwitchStmt sw, Body body, Deque<SymState> work, Set<Integer> targets) {
        SymValue key = eval(s, sw.getKey());
        List<Stmt> tgts = body.getBranchTargetsOf(sw);
        List<BoolExpr> notAny = new ArrayList<>();
        if (key.kind != SymValue.Kind.BV32) { s.next = tgts.isEmpty() ? null : tgts.get(tgts.size() - 1); work.push(s); return; }
        for (int i = 0; i < sw.getValueCount() && i < tgts.size(); i++) {
            BoolExpr c = ctx.mkEq(key.bv(), bv32(sw.getValue(i)));
            notAny.add(ctx.mkNot(c));
            SymState br = s.copy();
            br.pc.add(c);
            br.next = tgts.get(i);
            if (feasible(br)) work.push(br); else unsat++;
        }
        // default target is the last entry of the target list
        if (!tgts.isEmpty()) {
            SymState def = s;
            def.pc.addAll(notAny);
            def.next = tgts.get(tgts.size() - 1);
            if (feasible(def)) work.push(def); else unsat++;
        }
    }

    private static int lineOf(Stmt st) { return st == null ? -1 : st.getPositionInfo().getStmtPosition().getFirstLine(); }

    // ---------- calls ----------

    /** Handles an invocation; returns its symbolic result (null for void/unknown) or completes the state with an exception. */
    private SymValue invoke(SymState s, AbstractInvokeExpr inv, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        if (inv == null) return null;
        String owner = inv.getMethodSignature().getDeclClassType().toString();
        String name = inv.getMethodSignature().getName();
        String ret = inv.getMethodSignature().getType().toString();
        List<SymValue> args = new ArrayList<>();
        for (Immediate a : inv.getArgs()) args.add(eval(s, a));
        SymValue base = inv instanceof AbstractInstanceInvokeExpr ii ? eval(s, ii.getBase()) : null;
        // receiver null -> NullPointerException path
        if (base != null && base.nullFlag != null) {
            SymState npe = s.copy();
            npe.pc.add(base.nullFlag);
            raise(npe, "java.lang.NullPointerException");
            if (feasible(npe)) work.push(npe);
            s.pc.add(ctx.mkNot(base.nullFlag));
        }
        if (collections != null) {
            SymValue c = collections.apply(s, owner, name, base, args, as, stmts, work);
            if (c != null) return c;
        }
        SymValue r = JdkSummaries.apply(this, s, owner, name, base, args, ret);
        // boxing keeps the number and adds the class: a collection that takes the value then knows what it holds
        if (r != null && r.isNumeric() && name.equals("valueOf") && boxedKind(owner) != null) r = r.boxedAs(owner);
        if (r != null) return r;
        if (tryInline(s, inv, owner, name, base, args, as, work)) return INLINED;
        // the constructor of Object does nothing: an inlined constructor that reaches it stays precise
        if (cfg.objects().crossClassInline() && name.equals("<init>") && owner.equals("java.lang.Object")) return SymValue.unknown();
        // a constructor that is not followed may write any field: the object's unwritten fields are no longer defaults
        if (name.equals("<init>") && base != null && s.defaulted.remove(base.id)) note(s, "constructor " + owner.substring(owner.lastIndexOf('.') + 1) + " not followed");
        unmodeled.merge(owner + "." + name, 1, Integer::sum);
        note(s, "call " + owner.substring(owner.lastIndexOf('.') + 1) + "." + name);
        return freshForType(s, ret);
    }

    /**
     * CallHandler strategy 4 (plan 7.1): enter a same-class callee's body instead of returning a fresh
     * symbol, up to inlineDepth nested calls and never recursively. The caller's locals and position
     * are saved in a frame; the callee's identity statements pick up the arguments.
     */
    private boolean tryInline(SymState s, AbstractInvokeExpr inv, String owner, String name, SymValue base, List<SymValue> args, JAssignStmt as,
                              Deque<SymState> work) {
        boolean cross = cfg.objects().crossClassInline();
        if (cfg.inlineDepth() <= 0 || method == null || name.equals("<clinit>")) return false;   // depth 0 switches inlining off altogether
        boolean same = owner.equals(method.declaringClass());
        if (!same && (!cross || ofTheJdk(owner))) return false;
        // Calls within the class keep their depth. A call into another class gets one level more, so that a helper
        // of the class can hand over to it. Measured with depth 3 for every call: three times the inlined calls,
        // fewer paths finished in the same time, lower coverage (0929ObjectModel/REPORT.md).
        int depth = same && !name.equals("<init>") ? cfg.inlineDepth() : cfg.inlineDepth() + 1;
        if (s.depth() >= depth) return false;
        // a constructor is followed for the object it initializes: its fields are then what the constructor wrote
        if (name.equals("<init>") && (!cross || base == null || !base.hasFields)) return false;
        sootup.core.signatures.MethodSignature sig = inv.getMethodSignature();
        String key = sig.toString();
        if (key.equals(methodSignature) || s.inlining(key)) return false;
        java.util.Optional<Body> ob = java.util.Optional.empty();
        List<String> classes = List.of();
        try {
            boolean virtual = inv instanceof AbstractInstanceInvokeExpr && !(inv instanceof sootup.core.jimple.common.expr.JSpecialInvokeExpr);
            if (types != null && virtual && base != null && base.kind == SymValue.Kind.REF && base.id != 0) {
                String exact = base.exactType ? base.refType : s.exactly.get(base.id);
                if (exact != null) {
                    ob = types.oracle().body(exact, sig);
                } else {
                    ob = calleeBodies.apply(sig);
                    if (ob.isEmpty()) {
                        // the declared method has no body: the classes the object can be of decide what runs
                        classes = new ArrayList<>();
                        for (String c : classesOf(s, base, owner)) {
                            if (classes.size() >= MAX_DISPATCH) break;
                            if (types.oracle().body(c, sig).isPresent()) classes.add(c);
                        }
                        if (!classes.isEmpty()) ob = types.oracle().body(classes.get(0), sig);
                    }
                }
            } else {
                ob = calleeBodies.apply(sig);
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (ob.isEmpty()) return false;
        if (ob.get().getStmts().isEmpty()) return false;
        if (!same && ob.get().getStmts().size() > MAX_FOREIGN_STATEMENTS) return false;
        String resultLocal = as != null && as.getLeftOp() instanceof Local l ? l.getName() : null;
        Stmt callSite = as != null ? as : s.next;
        // one path per class the call can run in; each assumes its class
        for (int i = 1; i < classes.size(); i++) {
            java.util.Optional<Body> other = types.oracle().body(classes.get(i), sig);
            if (other.isEmpty() || other.get().getStmts().isEmpty() || other.get().getStmts().size() > MAX_FOREIGN_STATEMENTS) continue;
            SymState o = s.copy();
            types.assume(o, base, classes.get(i));
            enter(o, other.get(), key, base, args, callSite, resultLocal);
            if (feasible(o)) work.push(o);
        }
        if (!classes.isEmpty()) types.assume(s, base, classes.get(0));
        enter(s, ob.get(), key, base, args, callSite, resultLocal);
        return true;
    }

    /** The state moves into the body of a callee; the caller's locals and position wait in a frame. */
    private void enter(SymState s, Body callee, String key, SymValue base, List<SymValue> args, Stmt callSite, String resultLocal) {
        s.frames.push(new SymState.Frame(s.locals, s.body, s.stmts, callSite, resultLocal, key));
        s.locals = new java.util.HashMap<>();
        s.pendingArgs = args;
        s.pendingThis = base;
        s.body = callee;
        s.stmts = callee.getStmts();
        s.next = s.stmts.get(0);
        inlined++;
    }

    /**
     * The classes an object with an open class can be of: for the receiver and what it holds, the classes of the
     * candidate receivers; for anything else, the classes known to implement the declared type.
     */
    private List<String> classesOf(SymState s, SymValue base, String declared) {
        List<String> out = new ArrayList<>();
        if (base.entryPath != null && receivers != null && !base.entryPath.startsWith(STATIC)) {
            String root = rootOf(base.entryPath);
            List<String> path = base.entryPath.isEmpty() || base.entryPath.equals(root) ? List.of() : segments(base.entryPath);
            for (int k = 0; k < receivers.candidates(root); k++) {
                Object v;
                try {
                    v = receivers.read(root, k, path);
                } catch (RuntimeException e) {
                    continue;
                }
                String c = v instanceof ReceiverStates.Ref r ? r.className() : v instanceof ReceiverStates.Elements e ? e.className() : null;
                if (c != null && !out.contains(c)) out.add(c);
            }
            if (!out.isEmpty()) return out;
        }
        return types.oracle().implementations(declared);
    }

    SymValue freshForType(SymState s, String jimpleType) {
        switch (jimpleType) {
            case "void": return null;
            case "int": case "short": case "byte": case "char": return fresh(s, SymValue.Kind.BV32);
            case "boolean": { BitVecExpr b = ctx.mkBVConst("u" + (fresh++), 32); s.pc.add(ctx.mkOr(ctx.mkEq(b, bv32(0)), ctx.mkEq(b, bv32(1)))); return SymValue.bv32(b); }
            case "long": return fresh(s, SymValue.Kind.BV64);
            case "float": case "double": return fresh(s, SymValue.Kind.REAL);
            case "java.lang.String": case "java.lang.CharSequence": return SymValue.str(ctx.mkConst("u" + (fresh++) + "_s", ctx.getStringSort()), null);
            default: return jimpleType.endsWith("[]") ? SymValue.object(jimpleType, null) : SymValue.object(jimpleType, null);
        }
    }

    SymValue fresh(SymState s, SymValue.Kind k) {
        int n = fresh++;
        return switch (k) {
            case BV64 -> SymValue.bv64(ctx.mkBVConst("u" + n, 64));
            case REAL -> SymValue.real((RealExpr) ctx.mkConst("u" + n, ctx.getRealSort()));
            case STR -> SymValue.str(ctx.mkConst("u" + n + "_s", ctx.getStringSort()), null);
            default -> SymValue.bv32(ctx.mkBVConst("u" + n, 32));
        };
    }

    void note(SymState s, String what) { if (s.imprecise.size() < 20) s.imprecise.add(what); }

    // ---------- solving ----------

    /** The path condition as the solver gets it: conversions taken off the string terms, unless switched off. */
    private List<BoolExpr> toSolver(List<BoolExpr> pc) {
        if (lift == null) return pc;
        try {
            return lift.apply(pc);
        } catch (RuntimeException e) {
            // the rewrite is an optimisation: a term it cannot handle must not cost the path
            log.debug("bridge lift failed, formula sent as it is: {}", e.toString());
            lift.abandon();
            return pc;
        }
    }

    /** e as it has to be evaluated in a model of the formula that went to the solver (variables the lift turned into integers). */
    <R extends Sort> Expr<R> inModel(Expr<R> e) { return lift == null ? e : lift.forModel(e); }

    boolean feasible(SymState s) {
        solver.push();
        try {
            for (BoolExpr c : toSolver(s.pc)) solver.add(c);
            solves++;
            long t0 = System.nanoTime();
            Status st = solver.check();
            solverNanos += System.nanoTime() - t0;
            if (SmtDump.enabled()) SmtDump.record(solver, method == null ? null : method.id(), "feasible", st, System.nanoTime() - t0, cfg.solverTimeoutMs());
            if (st == Status.UNKNOWN) { solverTimeouts++; return false; }
            return st == Status.SATISFIABLE;
        } finally {
            solver.pop();
        }
    }

    private Model solve(SymState s) { return solve(s, List.of(), true); }

    /** @param extra further assertions for this call only; counted: whether an unsatisfiable answer counts as an infeasible path */
    private Model solve(SymState s, List<BoolExpr> extra, boolean counted) {
        solver.push();
        try {
            List<BoolExpr> pc = s.pc;
            if (!extra.isEmpty()) { pc = new ArrayList<>(s.pc); pc.addAll(extra); }
            for (BoolExpr c : toSolver(pc)) solver.add(c);
            solves++;
            long t0 = System.nanoTime();
            Status st = solver.check();
            solverNanos += System.nanoTime() - t0;
            if (SmtDump.enabled()) SmtDump.record(solver, method == null ? null : method.id(), "model", st, System.nanoTime() - t0, cfg.solverTimeoutMs());
            if (st == Status.SATISFIABLE) return solver.getModel();
            if (st == Status.UNKNOWN) solverTimeouts++; else if (counted) unsat++;
            return null;
        } finally {
            solver.pop();
        }
    }

    /**
     * Solves a completed path. When the path read fields of the receiver and candidate receivers are known, the
     * fields take the values of a candidate: the first candidate the path is feasible for is the receiver of the
     * test (chosen[0]). When no candidate fits, the path is solved with the fields left open, as it was before
     * there were candidates; the test then runs on the default receiver and may miss the path.
     */
    private Model solveWithReceiver(SymState s, Map<String, Integer> chosen) {
        if (receivers == null) return solve(s);
        // the objects the path depends on: the receiver, arguments, objects the receiver is built from
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        if (types != null && s.self != null && types.concerns(s, s.self)) roots.add("");
        for (String path : s.entries) { String r = rootOf(path); if (!r.equals(STATIC)) roots.add(r); }
        if (types != null) for (var e : s.params.entrySet()) if (types.concerns(s, e.getValue())) roots.add(ARG + e.getKey());
        // static fields hold what they hold whatever the objects are: facts of every attempt
        List<BoolExpr> facts = rootConstraints(s, STATIC, -1);
        if (facts == null) { unsat++; return null; }
        if (roots.isEmpty()) {
            if (!facts.isEmpty()) staticPaths++;
            return solve(s, facts, true);
        }
        receiverPaths++;
        List<BoolExpr> fixed = new ArrayList<>(facts);
        Model last = null;
        boolean allFitted = true, anyCandidates = false;
        for (String root : roots) {
            int n = receivers.candidates(root);
            if (n == 0) continue;   // nothing known about this object: its fields stay free
            anyCandidates = true;
            boolean found = false;
            Set<String> tried = new HashSet<>();
            for (int k = 0; k < n; k++) {
                List<BoolExpr> cs = rootConstraints(s, root, k);
                if (cs == null || !tried.add(cs.toString())) continue;   // candidates that agree on what the path reads are one attempt
                List<BoolExpr> all = new ArrayList<>(fixed);
                all.addAll(cs);
                Model m = solve(s, all, false);
                if (m != null) { chosen.put(root, k); fixed.addAll(cs); last = m; found = true; break; }
            }
            if (!found) {
                allFitted = false;
                for (String path : s.entries) if (rootOf(path).equals(root)) unmetFields.merge(path, 1, Integer::sum);
            }
        }
        if (!anyCandidates) return solve(s, fixed, true);
        if (allFitted) { receiverFitted++; return last; }
        // in a sequence the calls before the method are there to build the state: a path that still needs an entry
        // state no candidate has was reported when the method was explored on its own
        if (!prefix.isEmpty()) { receiverUnfitted++; return null; }
        Model m = solve(s, fixed, true);
        if (m != null) receiverUnfitted++;
        return m;
    }

    /** The object a root stands for on this path. */
    private SymValue rootObject(SymState s, String root) {
        if (root.startsWith(ARG)) return s.params.get(indexOf(root));
        if (root.startsWith(CTOR)) return s.ctorParams.get(indexOf(root));
        return s.self;
    }

    /**
     * The entry symbols below a root set to what candidate k holds (root STATIC: the static fields, k ignored);
     * null when the candidate cannot take the path at all.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<BoolExpr> rootConstraints(SymState s, String root, int k) {
        List<BoolExpr> out = new ArrayList<>();
        boolean statics = root.equals(STATIC);
        if (!statics && types != null && rootObject(s, root) != null) {
            Object self;
            try {
                self = receivers.read(root, k, List.of());
            } catch (RuntimeException ex) {
                self = ReceiverStates.UNKNOWN;
            }
            if (self instanceof ReceiverStates.Ref r) classConstraints(s, rootObject(s, root), r.className(), out);
        }
        for (String path : s.entries) {
            Entry e = entrySymbols.get(path);
            if (e == null || !rootOf(path).equals(root)) continue;
            Object v;
            try {
                v = statics ? receivers.read("", -1, List.of(path.split("/"))) : receivers.read(root, k, segments(path));
            } catch (RuntimeException ex) {
                v = ReceiverStates.UNKNOWN;
            }
            if (v == ReceiverStates.UNKNOWN) continue;
            if (v == ReceiverStates.BROKEN) return null;
            SymValue sym = e.value();
            switch (sym.kind) {
                case BV32: case BV64: {
                    Long n = v instanceof Number x && !(v instanceof Double) && !(v instanceof Float) ? Long.valueOf(x.longValue())
                            : v instanceof Character c ? Long.valueOf(c) : v instanceof Boolean b ? Long.valueOf(b ? 1 : 0) : null;
                    if (n != null) out.add(ctx.mkEq(sym.bv(), ctx.mkBV(n, sym.kind == SymValue.Kind.BV64 ? 64 : 32)));
                    break;
                }
                case REAL: {
                    if (v instanceof Number x && !Double.isNaN(x.doubleValue()) && !Double.isInfinite(x.doubleValue()))
                        out.add(ctx.mkEq((Expr) sym.expr, ctx.mkReal(new java.math.BigDecimal(x.doubleValue()).toPlainString())));
                    break;
                }
                case STR: {
                    if (v == null) { if (sym.nullFlag != null) out.add(sym.nullFlag); else return null; break; }
                    if (sym.nullFlag != null) out.add(ctx.mkNot(sym.nullFlag));
                    if (v instanceof CharSequence cs) out.add(ctx.mkEq((Expr) sym.expr, ctx.mkString(z3Literal(cs.toString()))));
                    break;
                }
                case REF: {
                    if (v == null) { if (sym.nullFlag != null) out.add(sym.nullFlag); else return null; break; }
                    if (sym.nullFlag != null) out.add(ctx.mkNot(sym.nullFlag));
                    if (sym.isArray() && v.getClass().isArray()) arrayConstraints(sym, v, out);
                    if (sym.isCollection() && collections != null && v instanceof ReceiverStates.Elements held) collections.constrain(sym, held, out);
                    if (types != null && v instanceof ReceiverStates.Ref r) classConstraints(s, sym, r.className(), out);
                    break;
                }
                default: break;
            }
        }
        return out;
    }

    /** What the path asked about the class of an object, answered by the class a concrete object has. */
    private void classConstraints(SymState s, SymValue object, String cls, List<BoolExpr> out) {
        String prefix = object.id + ":";
        for (var e : s.instanceOf.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            Boolean is = types.oracle().subtype(cls, e.getKey().substring(prefix.length()));
            if (is != null) out.add(is ? e.getValue() : ctx.mkNot(e.getValue()));
        }
    }

    /** Length and the first elements of a concrete array. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void arrayConstraints(SymValue sym, Object array, List<BoolExpr> out) {
        int n = java.lang.reflect.Array.getLength(array);
        out.add(ctx.mkEq(sym.array.length, bv32(n)));
        ArrayExpr data = sym.array.data;
        for (int i = 0; i < n && i < 64; i++) {
            Object x = java.lang.reflect.Array.get(array, i);
            Expr<?> c = null;
            switch (sym.array.elem) {
                case BV32: case BV64: {
                    Long v = x instanceof Number y && !(x instanceof Double) && !(x instanceof Float) ? Long.valueOf(y.longValue())
                            : x instanceof Character ch ? Long.valueOf(ch) : x instanceof Boolean b ? Long.valueOf(b ? 1 : 0) : null;
                    if (v != null) c = ctx.mkBV(v, sym.array.elem == SymValue.Kind.BV64 ? 64 : 32);
                    break;
                }
                case REAL:
                    if (x instanceof Number y && !Double.isNaN(y.doubleValue()) && !Double.isInfinite(y.doubleValue())) c = ctx.mkReal(new java.math.BigDecimal(y.doubleValue()).toPlainString());
                    break;
                case STR:
                    if (x instanceof CharSequence cs) c = ctx.mkString(z3Literal(cs.toString()));
                    break;
                default: break;
            }
            if (c != null) out.add(ctx.mkEq(ctx.mkSelect(data, bv32(i)), c));
        }
    }

    /** A Java string as the text of a Z3 string literal: everything outside printable ASCII, and the backslash, as an escape. */
    static String z3Literal(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != BACKSLASH) sb.append(c);
            else sb.append(BACKSLASH).append("u{").append(Integer.toHexString(c)).append('}');
        }
        return sb.toString();
    }

    private static final char BACKSLASH = 92;

    // ---------- model -> values ----------

    private SymResult.PathInput extract(MethodTarget m, SymState s, Model model, Map<String, Integer> chosen) {
        List<Value> args = new ArrayList<>();
        for (int i = 0; i < m.params().size(); i++) args.add(null);
        // the parameter symbols are kept in the state, so a path that ends inside an inlined callee still yields inputs
        for (var e : s.params.entrySet()) {
            int i = e.getKey();
            if (i >= m.params().size()) continue;
            Parameter p = m.params().get(i);
            args.set(i, valueOf(model, s, e.getValue(), p.type()));
        }
        List<List<Value>> before = new ArrayList<>();
        for (int k = 0; k < prefix.size(); k++) {
            MethodTarget pm = prefix.get(k).method();
            List<Value> pa = new ArrayList<>();
            for (int i = 0; i < pm.params().size(); i++) {
                SymValue sym = prefix.get(k).constructs() ? s.ctorParams.get(i) : s.prefixParams.get(k * 256 + i);
                pa.add(sym == null ? null : valueOf(model, s, sym, pm.params().get(i).type()));
            }
            before.add(pa);
        }
        return new SymResult.PathInput(args, s.lines, s.thrown, !s.imprecise.isEmpty(), List.copyOf(s.imprecise), chosen.getOrDefault("", -1), before,
                Map.copyOf(chosen));
    }

    @SuppressWarnings("unchecked")
    private Value valueOf(Model model, SymState s, SymValue v, TypeRef t) {
        if (v == null) return null;
        String e = t.erasureName();
        try {
            if (v.kind == SymValue.Kind.BV32 || v.kind == SymValue.Kind.BV64) {
                long n = ((BitVecNum) model.eval(inModel(v.expr), true)).getBigInteger().longValue();
                return numeric(e, n);
            }
            if (v.kind == SymValue.Kind.REAL) {
                Expr<?> r = model.eval(v.expr, true);
                double d = r instanceof RatNum rn ? Double.parseDouble(rn.toDecimalString(12).replace("?", "")) : 0;
                return e.equals("float") || e.equals("java.lang.Float") ? PrimitiveValue.of((float) d) : PrimitiveValue.of(d);
            }
            if (v.kind == SymValue.Kind.STR) {
                if (v.nullFlag != null && model.eval(v.nullFlag, true).isTrue()) return new NullValue(t);
                String str = model.eval(v.expr, true).getString();
                return new StringValue(str);
            }
            if (v.kind == SymValue.Kind.REF) {
                if (v.isDefinitelyNull() || (v.nullFlag != null && model.eval(v.nullFlag, true).isTrue())) return new NullValue(t);
                if (v.isCollection() && collections != null && s != null) return collections.value(model, s, v, t, values);
                if (v.isArray() && t instanceof TypeRef.ArrayRef ar) {
                    int len = (int) ((BitVecNum) model.eval(inModel(v.array.length), true)).getBigInteger().longValue();
                    len = Math.max(0, Math.min(len, cfg.maxArrayLength()));
                    List<Value> elems = new ArrayList<>();
                    ArrayExpr<BitVecSort, Sort> data = (ArrayExpr<BitVecSort, Sort>) v.array.data;
                    for (int i = 0; i < len; i++) {
                        Expr<?> el = model.eval(ctx.mkSelect(data, bv32(i)), true);
                        elems.add(elementValue(el, v.array.elem, ar.component()));
                    }
                    return new ArrayValue(ar.component(), elems);
                }
                if (types != null && s != null && types.concerns(s, v)) return types.value(model, s, v, t, values);
                return null; // object: let a generator build it
            }
        } catch (RuntimeException ex) {
            log.debug("model extraction failed for {}: {}", t, ex.toString());
        }
        return null;
    }

    Value elementValue(Expr<?> el, SymValue.Kind kind, TypeRef comp) {
        String e = comp.erasureName();
        switch (kind) {
            case STR: return new StringValue(el.getString());
            case REAL: { double d = el instanceof RatNum rn ? Double.parseDouble(rn.toDecimalString(12).replace("?", "")) : 0; return e.equals("float") ? PrimitiveValue.of((float) d) : PrimitiveValue.of(d); }
            default: return numeric(e, el instanceof BitVecNum bn ? bn.getBigInteger().longValue() : 0);
        }
    }

    private static Value numeric(String type, long n) {
        switch (type) {
            case "int": case "java.lang.Integer": return PrimitiveValue.of((int) n);
            case "long": case "java.lang.Long": return PrimitiveValue.of(n);
            case "short": case "java.lang.Short": return PrimitiveValue.of((short) n);
            case "byte": case "java.lang.Byte": return PrimitiveValue.of((byte) n);
            case "char": case "java.lang.Character": return PrimitiveValue.of((char) n);
            case "boolean": case "java.lang.Boolean": return PrimitiveValue.of(n != 0);
            default: return PrimitiveValue.of((int) n);
        }
    }

    // ---------- helpers used by summaries ----------

    Context ctx() { return ctx; }
    BitVecExpr bv32(long v) { return ctx.mkBV(v, 32); }
    IntExpr toInt(BitVecExpr b) { return ctx.mkBV2Int(b, true); }
    BitVecExpr toBv32(Expr<?> i) { return ctx.mkInt2BV(32, (Expr<com.microsoft.z3.IntSort>) i); }
    @SuppressWarnings("unchecked") SeqExpr<CharSort> seq(SymValue v) { return (SeqExpr<CharSort>) (Expr<?>) v.expr; }
}
