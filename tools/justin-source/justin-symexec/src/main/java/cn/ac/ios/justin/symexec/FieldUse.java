package cn.ac.ios.justin.symexec;

import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.ref.JArrayRef;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Which fields a method reads and which it writes, calls of methods of the same class included. A method that
 * writes what another one reads is a candidate for a call before it: it can bring the receiver into a state the
 * constructor does not produce.
 *
 * <p>Fields are named declaringClass#name. Storing into an array a field holds, or calling a method that is not
 * known to be read-only on the object a field holds, counts as writing the field: the state behind it changes.
 */
final class FieldUse {

    record Use(Set<String> reads, Set<String> writes) {}

    /** Methods of objects held in fields that leave the object as it is. */
    private static final Set<String> READ_ONLY = Set.of("size", "isEmpty", "get", "getOrDefault", "contains", "containsKey", "containsValue", "containsAll",
            "length", "charAt", "equals", "hashCode", "toString", "iterator", "listIterator", "indexOf", "lastIndexOf", "keySet", "values", "entrySet",
            "peek", "peekFirst", "peekLast", "first", "last", "element", "getFirst", "getLast", "startsWith", "endsWith", "compareTo", "stream", "forEach",
            "toArray", "subList", "hasNext", "getClass", "name", "ordinal", "intValue", "longValue", "doubleValue", "booleanValue", "isPresent", "capacity");

    private final Function<MethodSignature, Optional<Body>> bodies;
    private final Map<String, Use> known = new HashMap<>();

    FieldUse(Function<MethodSignature, Optional<Body>> bodies) { this.bodies = bodies; }

    /** Reads and writes of the body, with those of the same-class methods it calls, directly or not. */
    Use of(Body body) {
        return of(body, new LinkedHashSet<>());
    }

    private Use of(Body body, Set<String> onStack) {
        String key = body.getMethodSignature().toString();
        Use done = known.get(key);
        if (done != null) return done;
        if (!onStack.add(key) || onStack.size() > 12) return new Use(Set.of(), Set.of());
        String owner = body.getMethodSignature().getDeclClassType().toString();
        Set<String> reads = new LinkedHashSet<>(), writes = new LinkedHashSet<>();
        Map<String, String> heldBy = new LinkedHashMap<>();   // local -> the field it was read from
        for (Stmt st : body.getStmts()) {
            AbstractInvokeExpr inv = null;
            if (st instanceof JAssignStmt as) {
                Value l = as.getLeftOp(), r = as.getRightOp();
                if (l instanceof JInstanceFieldRef fr) writes.add(name(fr));
                else if (l instanceof JArrayRef ar && heldBy.containsKey(ar.getBase().getName())) writes.add(heldBy.get(ar.getBase().getName()));
                if (r instanceof JInstanceFieldRef fr) {
                    reads.add(name(fr));
                    if (l instanceof Local ll) heldBy.put(ll.getName(), name(fr));
                } else if (l instanceof Local ll) {
                    if (r instanceof Local rl && heldBy.containsKey(rl.getName())) heldBy.put(ll.getName(), heldBy.get(rl.getName()));
                    else heldBy.remove(ll.getName());
                }
                if (r instanceof AbstractInvokeExpr e) inv = e;
            } else if (st instanceof JInvokeStmt is) {
                inv = is.getInvokeExpr().orElse(null);
            }
            if (inv == null) continue;
            MethodSignature sig = inv.getMethodSignature();
            if (inv instanceof AbstractInstanceInvokeExpr ii && heldBy.containsKey(ii.getBase().getName()) && !READ_ONLY.contains(sig.getName())) {
                writes.add(heldBy.get(ii.getBase().getName()));
            }
            if (sig.getDeclClassType().toString().equals(owner) && !sig.getName().equals("<init>") && !sig.getName().equals("<clinit>")) {
                Optional<Body> callee;
                try {
                    callee = bodies.apply(sig);
                } catch (RuntimeException e) {
                    callee = Optional.empty();
                }
                if (callee.isPresent()) {
                    Use u = of(callee.get(), onStack);
                    reads.addAll(u.reads());
                    writes.addAll(u.writes());
                }
            }
        }
        onStack.remove(key);
        Use u = new Use(Set.copyOf(reads), Set.copyOf(writes));
        // a result computed while a caller is still on the stack may lack what the recursion would add: not kept
        if (onStack.isEmpty()) known.put(key, u);
        return u;
    }

    static String name(JInstanceFieldRef fr) {
        return fr.getFieldSignature().getDeclClassType() + "#" + fr.getFieldSignature().getName();
    }
}
