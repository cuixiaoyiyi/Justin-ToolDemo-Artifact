package cn.ac.ios.justin.symexec;

import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.ref.JParameterRef;
import sootup.core.jimple.common.ref.JStaticFieldRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIdentityStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt;
import sootup.core.model.Body;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Says what a target line's controlling branch condition depends on, so unreached targets can be
 * attributed to a missing capability rather than lumped together: string operations, collections and
 * iterators, object state (fields), other calls the executor does not model, or only values it does
 * model (numbers, arrays, null checks). The dependence is a def-use closure over the body, without
 * regard to paths: cheap, and enough to rank what to build next.
 */
public final class TargetAttribution {
    private TargetAttribution() {}

    public static final String STRING = "string", COLLECTION = "collection", FIELD = "object-state", CALL = "other-call", MODELED = "modeled", UNCONDITIONAL = "unconditional", UNKNOWN = "unknown";

    /** line -> cause, for the lines that occur in the body; other lines are absent from the result. */
    public static Map<Integer, String> attribute(Body body, String declaringClass, Set<Integer> lines) {
        List<Stmt> stmts = body.getStmts();
        Map<String, List<Stmt>> defs = new HashMap<>();
        Set<Integer> present = new HashSet<>();
        for (Stmt s : stmts) {
            present.add(lineOf(s));
            if (s instanceof JAssignStmt as && as.getLeftOp() instanceof Local l) defs.computeIfAbsent(l.getName(), k -> new ArrayList<>()).add(s);
            if (s instanceof JIdentityStmt id) defs.computeIfAbsent(id.getLeftOp().getName(), k -> new ArrayList<>()).add(s);
        }
        Map<Integer, String> out = new LinkedHashMap<>();
        for (int line : lines) {
            if (!present.contains(line)) continue;
            List<Stmt> controls = controllers(body, stmts, line);
            // no branch before the line: it runs whenever the method is entered, so what is missing is a successful invocation (receiver or arguments)
            if (controls.isEmpty()) { out.put(line, UNCONDITIONAL); continue; }
            Set<String> causes = new HashSet<>();
            for (Stmt c : controls) causes.addAll(dependencies(c, defs, body, declaringClass));
            out.put(line, primary(causes));
        }
        return out;
    }

    private static String primary(Set<String> causes) {
        for (String c : List.of(STRING, COLLECTION, FIELD, CALL)) if (causes.contains(c)) return c;
        return MODELED;
    }

    /** Branching statements on the line itself (a partly covered condition), else those with a successor on the line, else the last one before it. */
    private static List<Stmt> controllers(Body body, List<Stmt> stmts, int line) {
        List<Stmt> same = new ArrayList<>(), into = new ArrayList<>();
        Stmt lastBefore = null;
        boolean seenLine = false;
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            boolean branching = s instanceof JIfStmt || s instanceof JSwitchStmt;
            if (lineOf(s) == line) { seenLine = true; if (branching) same.add(s); }
            if (!branching) continue;
            if (!seenLine) lastBefore = s;
            List<Stmt> succ = new ArrayList<>(s instanceof JIfStmt ifs ? body.getBranchTargetsOf(ifs) : body.getBranchTargetsOf((JSwitchStmt) s));
            if (i + 1 < stmts.size()) succ.add(stmts.get(i + 1));
            for (Stmt t : succ) if (lineOf(t) == line && lineOf(s) != line) { into.add(s); break; }
        }
        if (!same.isEmpty()) return same;
        if (!into.isEmpty()) return into;
        return lastBefore == null ? List.of() : List.of(lastBefore);
    }

    /** Def-use closure from the condition's operands to parameters, fields and calls, as cause labels. */
    private static Set<String> dependencies(Stmt control, Map<String, List<Stmt>> defs, Body body, String declaringClass) {
        Set<String> causes = new HashSet<>();
        Deque<Value> work = new ArrayDeque<>();
        Set<String> seenLocals = new HashSet<>();
        Value cond = control instanceof JIfStmt ifs ? ifs.getCondition() : control instanceof JSwitchStmt sw ? sw.getKey() : null;
        if (cond == null) return causes;
        work.push(cond);
        int budget = 400;
        while (!work.isEmpty() && budget-- > 0) {
            Value v = work.pop();
            if (v instanceof Local l) {
                if (!seenLocals.add(l.getName())) continue;
                typeCause(l.getType().toString(), causes, false);
                for (Stmt d : defs.getOrDefault(l.getName(), List.of())) {
                    if (d instanceof JAssignStmt as) work.push(as.getRightOp());
                    if (d instanceof JIdentityStmt id) work.push(id.getRightOp());
                }
                continue;
            }
            if (v instanceof JParameterRef pr) { typeCause(pr.getType().toString(), causes, true); continue; }
            if (v instanceof JInstanceFieldRef fr) { causes.add(FIELD); work.push(fr.getBase()); continue; }
            if (v instanceof JStaticFieldRef) { causes.add(FIELD); continue; }
            if (v instanceof AbstractInvokeExpr inv) {
                String owner = inv.getMethodSignature().getDeclClassType().toString();
                if (isStringType(owner) || owner.equals("java.lang.Character")) causes.add(STRING);
                else if (isCollectionType(owner)) causes.add(COLLECTION);
                else if (owner.equals(declaringClass)) { /* same-class call: inlined by the executor */ }
                else if (owner.equals("java.lang.Math") || owner.equals("java.lang.StrictMath") || owner.equals("java.util.Objects")
                        || owner.equals("java.lang.Integer") || owner.equals("java.lang.Long") || owner.equals("java.lang.Boolean")
                        || owner.equals("java.lang.Short") || owner.equals("java.lang.Byte") || owner.equals("java.lang.Double") || owner.equals("java.lang.Float")) { /* summarized */ }
                else causes.add(CALL);
            }
            v.getUses().forEach(work::push);
        }
        return causes;
    }

    private static void typeCause(String type, Set<String> causes, boolean parameter) {
        if (isStringType(type)) causes.add(STRING);
        else if (isCollectionType(type)) causes.add(COLLECTION);
    }

    private static boolean isStringType(String t) {
        return t.equals("java.lang.String") || t.equals("java.lang.CharSequence") || t.equals("java.lang.StringBuilder") || t.equals("java.lang.StringBuffer")
                || t.equals("java.lang.AbstractStringBuilder");
    }

    private static boolean isCollectionType(String t) {
        if (t.equals("java.lang.Iterable")) return true;
        if (!t.startsWith("java.util.")) return false;
        String n = t.substring("java.util.".length());
        return n.endsWith("List") || n.endsWith("Set") || n.endsWith("Map") || n.endsWith("Collection") || n.endsWith("Queue") || n.endsWith("Deque")
                || n.endsWith("Iterator") || n.equals("Iterable") || n.endsWith("Stack") || n.endsWith("Vector") || n.startsWith("Map$") || n.equals("Enumeration");
    }

    private static int lineOf(Stmt st) { return st.getPositionInfo().getStmtPosition().getFirstLine(); }
}
