package cn.ac.ios.justin.analysis;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Relation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.constant.DoubleConstant;
import sootup.core.jimple.common.constant.FloatConstant;
import sootup.core.jimple.common.constant.IntConstant;
import sootup.core.jimple.common.constant.LongConstant;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.expr.JLengthExpr;
import sootup.core.jimple.common.expr.JNewArrayExpr;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.expr.JNewMultiArrayExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JThisRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JReturnStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;
import sootup.java.core.JavaSootMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a Jimple body once and records constants, branches, callees, explicit throws, parameter
 * relations and two conservative properties. Flow-insensitive: no path conditions, one pass.
 */
public final class FactExtractor {
    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);

    /** Callee name fragments that signal a precondition helper. */
    private static final List<String> PRECONDITION_HELPERS = List.of(
            "java.util.Objects: java.lang.Object requireNonNull", "java.util.Objects: int checkIndex",
            "Validate: ", "Preconditions: ", "Assert: ", "checkArgument", "checkState", "checkNotNull", "requireNonNull");

    /** Callee owners considered side-effect free for the purity heuristic. */
    private static final Set<String> PURE_OWNERS = Set.of(
            "java.lang.String", "java.lang.Math", "java.lang.StrictMath", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean", "java.lang.Objects",
            "java.util.Objects", "java.lang.CharSequence", "java.lang.Object", "java.lang.Enum", "java.lang.Number");

    private final SootUpLoader loader;

    public FactExtractor(SootUpLoader loader) { this.loader = loader; }

    public MethodFacts extract(MethodTarget target) {
        Optional<JavaSootMethod> om = loader.loadMethod(target);
        if (om.isEmpty() || !om.get().hasBody()) return MethodFacts.unavailable(target.id());
        Body body;
        try {
            body = om.get().getBody();
        } catch (RuntimeException e) {
            log.warn("body construction failed for {}: {}", target.id(), e.toString());
            return MethodFacts.unavailable(target.id());
        }
        List<Stmt> stmts = body.getStmts();
        Walk w = new Walk(target, body, stmts);
        w.run();
        return new MethodFacts(target.id(), stmts.size(), w.branches, w.branchLines, w.ints, w.longs, w.doubles, w.strings, w.callees,
                w.throwsTypes, w.precondition(), w.relations, w.neverReturnsNull(), w.pure(), true);
    }

    /** One pass over the statements with the little bookkeeping the patterns need. */
    private static final class Walk {
        final MethodTarget target;
        final Body body;
        final List<Stmt> stmts;
        int branches;
        final List<Integer> branchLines = new ArrayList<>();
        final Set<Integer> ints = new LinkedHashSet<>();
        final Set<Long> longs = new LinkedHashSet<>();
        final Set<Double> doubles = new LinkedHashSet<>();
        final Set<String> strings = new LinkedHashSet<>();
        final Set<String> callees = new LinkedHashSet<>();
        final Set<String> throwsTypes = new LinkedHashSet<>();
        final List<Relation> relations = new ArrayList<>();
        final Map<String, String> newTypeOfLocal = new HashMap<>();   // local -> type created by `new`
        final Map<String, Integer> paramOf = new HashMap<>();          // local -> parameter index (identity stmt or plain copy)
        final Map<String, Integer> lengthOf = new HashMap<>();         // local -> parameter whose length/size it holds
        final Map<String, Set<String>> defKinds = new HashMap<>();     // local -> kinds of right-hand sides defining it
        final Set<Integer> dependents = new HashSet<>();
        boolean fieldWrite, unknownCall;
        final List<JReturnStmt> returns = new ArrayList<>();

        Walk(MethodTarget target, Body body, List<Stmt> stmts) { this.target = target; this.body = body; this.stmts = stmts; }

        void run() {
            // pass 1: parameter locals, copies, lengths, news
            for (Stmt s : stmts) {
                if (s instanceof JIdentityStmt id && id.getRightOp() instanceof JParameterRef pr) paramOf.put(id.getLeftOp().toString(), pr.getIndex());
            }
            for (Stmt s : stmts) {
                if (!(s instanceof JAssignStmt as)) continue;
                String lhs = as.getLeftOp().toString();
                Value rhs = as.getRightOp();
                defKinds.computeIfAbsent(lhs, k -> new HashSet<>()).add(kind(rhs));
                if (rhs instanceof Local l && paramOf.containsKey(l.getName())) paramOf.put(lhs, paramOf.get(l.getName()));
                if (rhs instanceof JNewExpr ne) newTypeOfLocal.put(lhs, ne.getType().toString());
                if (rhs instanceof JLengthExpr le) { Integer p = param(le.getOp()); if (p != null) lengthOf.put(lhs, p); }
                if (rhs instanceof AbstractInstanceInvokeExpr inv) {
                    String name = inv.getMethodSignature().getName();
                    Integer p = param(inv.getBase());
                    if (p != null && (name.equals("length") || name.equals("size")) && inv.getArgCount() == 0) lengthOf.put(lhs, p);
                }
                if (as.getLeftOp() instanceof JFieldRef || as.getLeftOp() instanceof JArrayRef) fieldWrite = true;
            }
            // pass 2: everything else
            for (int i = 0; i < stmts.size(); i++) {
                Stmt s = stmts.get(i);
                if (s instanceof JIfStmt || s instanceof JSwitchStmt) {
                    branches++;
                    branchLines.add(s.getPositionInfo().getStmtPosition().getFirstLine());
                }
                if (s instanceof JIfStmt ifs) comparison(ifs, ifs.getCondition(), i);
                if (s instanceof JThrowStmt ts) {
                    String t = newTypeOfLocal.get(ts.getOp().toString());
                    throwsTypes.add(t != null ? t : ts.getOp().getType().toString());
                }
                if (s instanceof JReturnStmt r) returns.add(r);
                if (s.containsArrayRef()) {
                    JArrayRef ar = s.getArrayRef();
                    Integer base = param(ar.getBase()), idx = param(ar.getIndex());
                    if (base != null && idx != null) relation(Relation.Kind.INDEX_OF, idx, base, "array-access");
                }
                if (s.isInvokableStmt() && s.asInvokableStmt().getInvokeExpr().isPresent()) {
                    AbstractInvokeExpr inv = s.asInvokableStmt().getInvokeExpr().get();
                    invocation(inv);
                }
                s.getUses().forEach(this::collect);
            }
        }

        private void comparison(JIfStmt ifs, AbstractBinopExpr cond, int index) {
            Integer p1 = param(cond.getOp1()), p2 = param(cond.getOp2());
            Integer len1 = lengthOf.get(cond.getOp1().toString()), len2 = lengthOf.get(cond.getOp2().toString());
            String sym = cond.getSymbol().trim();
            boolean trueThrows = leadsToThrow(body.getBranchTargetsOf(ifs).isEmpty() ? null : body.getBranchTargetsOf(ifs).get(0));
            boolean falseThrows = index + 1 < stmts.size() && leadsToThrow(stmts.get(index + 1));
            if (p1 != null && len2 != null && isInt(cond.getOp1())) {
                relation(sym.equals("<=") || sym.equals(">") ? Relation.Kind.LENGTH_LE : Relation.Kind.INDEX_OF, p1, len2, "compare-length");
            } else if (p2 != null && len1 != null && isInt(cond.getOp2())) {
                relation(sym.equals(">=") || sym.equals("<") ? Relation.Kind.LENGTH_LE : Relation.Kind.INDEX_OF, p2, len1, "compare-length");
            } else if (len1 != null && len2 != null && !len1.equals(len2) && (sym.equals("==") || sym.equals("!="))) {
                relation(Relation.Kind.SAME_LENGTH, len2, len1, "compare-lengths");
            } else if (p1 != null && p2 != null && !p1.equals(p2) && isInt(cond.getOp1()) && isInt(cond.getOp2())) {
                // p1 <op> p2 guarding a throw: the throwing side is the violation, the other the contract
                if (trueThrows || falseThrows) {
                    boolean violationIsGreater = (sym.equals(">") || sym.equals(">=")) == trueThrows;
                    // violation "p1 > p2" means legal p1 <= p2: derive p2 from p1 (RANGE) or, if p2 is
                    // already derived by another relation, p1 from p2 (RANGE_BELOW)
                    int lo = violationIsGreater ? p1 : p2, hi = violationIsGreater ? p2 : p1;
                    if (!dependents.contains(hi)) relation(Relation.Kind.RANGE, hi, lo, "guarded-compare");
                    else relation(Relation.Kind.RANGE_BELOW, lo, hi, "guarded-compare");
                } else {
                    if (!dependents.contains(p2)) relation(Relation.Kind.RANGE, p2, p1, "compare");
                    else relation(Relation.Kind.RANGE_BELOW, p1, p2, "compare");
                }
            }
        }

        private void invocation(AbstractInvokeExpr inv) {
            String name = inv.getMethodSignature().getName();
            String owner = inv.getMethodSignature().getDeclClassType().toString();
            if (!(inv instanceof AbstractInstanceInvokeExpr iinv)) return;
            Integer base = param(iinv.getBase());
            if (base == null || inv.getArgCount() == 0) return;
            Integer a0 = param(inv.getArg(0));
            if (a0 == null) return;
            boolean mapLike = owner.startsWith("java.util.") && owner.endsWith("Map");
            boolean seqLike = owner.equals("java.lang.String") || owner.equals("java.lang.CharSequence") || owner.equals("java.lang.StringBuilder")
                    || owner.equals("java.util.List") || owner.equals("java.util.ArrayList");
            if (mapLike && (name.equals("get") || name.equals("containsKey") || name.equals("remove") || name.equals("getOrDefault"))) {
                relation(Relation.Kind.KEY_OF, a0, base, "map-lookup");
            } else if (seqLike && (name.equals("charAt") || name.equals("get") || name.equals("codePointAt") || name.equals("remove") && owner.contains("List"))) {
                relation(Relation.Kind.INDEX_OF, a0, base, "element-access");
            } else if (seqLike && (name.equals("substring") || name.equals("subList") || name.equals("subSequence"))) {
                relation(Relation.Kind.LENGTH_LE, a0, base, "sub-range");
                if (inv.getArgCount() > 1) { Integer a1 = param(inv.getArg(1)); if (a1 != null) relation(Relation.Kind.LENGTH_LE, a1, base, "sub-range"); }
            }
        }

        private void relation(Relation.Kind k, int dependent, int base, String source) {
            if (dependent == base || dependents.contains(dependent)) return;
            dependents.add(dependent);
            relations.add(new Relation(k, dependent, base, source));
        }

        /** Does the block starting at s throw within a few statements, without branching away first? */
        private boolean leadsToThrow(Stmt s) {
            if (s == null) return false;
            int i = stmts.indexOf(s);
            for (int k = i; k < stmts.size() && k < i + 8; k++) {
                Stmt t = stmts.get(k);
                if (t instanceof JThrowStmt) return true;
                if (t instanceof JAssignStmt as && as.getRightOp() instanceof JNewExpr ne && ne.getType().toString().endsWith("Exception")) return true;
                if (t.branches() || t instanceof JReturnStmt) return false;
            }
            return false;
        }

        private Integer param(Value v) {
            return v instanceof Local l ? paramOf.get(l.getName()) : null;
        }

        private static boolean isInt(Value v) {
            String t = v.getType().toString();
            return t.equals("int") || t.equals("long") || t.equals("short") || t.equals("byte");
        }

        private void collect(Value v) {
            if (v instanceof IntConstant c) ints.add(c.getValue());
            else if (v instanceof LongConstant c) longs.add(c.getValue());
            else if (v instanceof DoubleConstant c) doubles.add(c.getValue());
            else if (v instanceof FloatConstant c) doubles.add((double) c.getValue());
            else if (v instanceof StringConstant c) strings.add(c.getValue());
            else if (v instanceof AbstractInvokeExpr inv) {
                String sig = inv.getMethodSignature().toString();
                callees.add(sig);
                if (inv instanceof sootup.core.jimple.common.expr.JDynamicInvokeExpr dyn) {
                    // Java 9+ string concatenation: the literal fragments live in the bootstrap recipe
                    for (Value b : dyn.getBootstrapArgs()) {
                        if (b instanceof StringConstant sc) {
                            for (String frag : sc.getValue().split("[\\u0001\\u0002]")) if (!frag.isEmpty()) strings.add(frag);
                        }
                    }
                }
                String owner = inv.getMethodSignature().getDeclClassType().toString();
                boolean exceptionCtor = inv.getMethodSignature().getName().equals("<init>") && (owner.endsWith("Exception") || owner.endsWith("Error"));
                boolean concat = owner.startsWith("java.lang.invoke.") || owner.contains("InvokeDynamic") || owner.equals("java.lang.StringBuilder");
                if (!PURE_OWNERS.contains(owner) && !exceptionCtor && !concat) unknownCall = true;
            }
            v.getUses().forEach(this::collect);
        }

        private static String kind(Value rhs) {
            if (rhs instanceof JNewExpr || rhs instanceof JNewArrayExpr || rhs instanceof JNewMultiArrayExpr) return "new";
            if (rhs instanceof StringConstant) return "const";
            if (rhs instanceof JThisRef) return "this";
            return "other";
        }

        boolean precondition() { return callees.stream().anyMatch(c -> PRECONDITION_HELPERS.stream().anyMatch(c::contains)); }

        /** Every return is a string constant, a freshly created object, or `this`. */
        boolean neverReturnsNull() {
            if (returns.isEmpty() || target.returnType().isPrimitive() || target.returnsVoid()) return false;
            for (JReturnStmt r : returns) {
                Value op = r.getOp();
                if (op instanceof StringConstant) continue;
                if (op instanceof Local l) {
                    Set<String> kinds = defKinds.get(l.getName());
                    if (kinds != null && !kinds.isEmpty() && kinds.stream().allMatch(k -> k.equals("new") || k.equals("const") || k.equals("this"))) continue;
                    if (body.getThisLocal() != null && l.getName().equals(body.getThisLocal().getName())) continue;
                }
                return false;
            }
            return true;
        }

        boolean pure() { return !fieldWrite && !unknownCall; }
    }
}
