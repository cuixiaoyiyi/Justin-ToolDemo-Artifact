package cn.ac.ios.justin.symexec;

import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BoolExpr;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One path's state: locals, heap, path condition, position, counters, and the stack of inlined callers.
 * Copied on every fork. {@code body}/{@code stmts} are those of the method currently being
 * interpreted (the target, or an inlined callee); {@code params} keeps the target method's parameter
 * symbols so inputs can be extracted whatever frame the path ends in.
 */
final class SymState {
    /** A caller suspended by inlining: its locals and position, restored when the callee returns. */
    record Frame(Map<String, SymValue> savedLocals, Body body, List<Stmt> stmts, Stmt callSite, String resultLocal, String calleeSignature) {}

    Map<String, SymValue> locals;
    final List<BoolExpr> pc;
    final Map<Stmt, Integer> visits;
    final Set<Integer> lines;
    final List<String> imprecise;
    final Map<Integer, SymValue> params;
    final Deque<Frame> frames;
    /** Fields by object identity: what this path wrote or read. Copied on a fork, so a write on one path is not seen on another. */
    final Map<Integer, Map<String, SymValue>> heap;
    /** Array contents by array identity, where this path stored into the array. */
    final Map<Integer, ArrayExpr<?, ?>> arrays;
    /** Chains of fields (from the receiver) whose entry value this path read: what a concrete receiver has to supply. */
    final Set<String> entries;
    /** What the path found out about the class of an object: "id:type" -> the condition under which the object is an instance of it. */
    final Map<String, BoolExpr> instanceOf;
    /** Collections, iterators and entries by identity, where this path changed or typed them. */
    final Map<Integer, SymValue.CollInfo> colls;
    /** Collections whose size this path has bounded (the operations that look at every element need a bound). */
    final Set<Integer> bounded;
    /** Contents of objects that were cast to String or a boxed type, by identity and kind; and the type of the cast. */
    final Map<String, SymValue> views;
    final Map<String, String> viewTypes;
    /** Objects the path assumes to be of exactly one class (a virtual call was resolved for that class). */
    final Map<Integer, String> exactly;
    /** Symbols of the constructor's parameters, when the constructor of the receiver is part of the sequence. */
    final Map<Integer, SymValue> ctorParams;
    /** Objects whose constructor the path followed from the start: fields nothing wrote hold the defaults. */
    final Set<Integer> defaulted;
    /** Arguments of the calls made before the target (see SymbolicExecutor.Prefix), by call * 256 + parameter. */
    final Map<Integer, SymValue> prefixParams;
    /** Which call of the sequence the path is in; the number of prefix calls means the target itself. */
    int stage;
    /** The receiver, the same object in every call of the sequence. */
    SymValue self;
    Body body;
    List<Stmt> stmts;
    Stmt next;
    int steps;
    String thrown;          // exception type when the path ended with a throw
    String caught;          // exception type a handler is about to receive (set by raise, taken by the handler's identity statement)
    boolean completed;
    // arguments handed to an inlined callee, consumed by its identity statements
    List<SymValue> pendingArgs;
    SymValue pendingThis;

    SymState(Body body, List<Stmt> stmts, Stmt start) {
        locals = new HashMap<>();
        pc = new ArrayList<>();
        visits = new HashMap<>();
        lines = new LinkedHashSet<>();
        imprecise = new ArrayList<>();
        params = new HashMap<>();
        frames = new ArrayDeque<>();
        heap = new HashMap<>();
        arrays = new HashMap<>();
        entries = new LinkedHashSet<>();
        instanceOf = new HashMap<>();
        prefixParams = new HashMap<>();
        ctorParams = new HashMap<>();
        defaulted = new HashSet<>();
        colls = new HashMap<>();
        bounded = new HashSet<>();
        views = new HashMap<>();
        viewTypes = new HashMap<>();
        exactly = new HashMap<>();
        this.body = body;
        this.stmts = stmts;
        next = start;
    }

    private SymState(SymState o) {
        locals = new HashMap<>(o.locals);
        pc = new ArrayList<>(o.pc);
        visits = new HashMap<>(o.visits);
        lines = new LinkedHashSet<>(o.lines);
        imprecise = new ArrayList<>(o.imprecise);
        params = new HashMap<>(o.params);
        frames = new ArrayDeque<>(o.frames);
        heap = new HashMap<>(o.heap.size() * 2);
        for (var e : o.heap.entrySet()) heap.put(e.getKey(), new HashMap<>(e.getValue()));
        arrays = new HashMap<>(o.arrays);
        entries = new LinkedHashSet<>(o.entries);
        instanceOf = new HashMap<>(o.instanceOf);
        prefixParams = new HashMap<>(o.prefixParams);
        ctorParams = new HashMap<>(o.ctorParams);
        defaulted = new HashSet<>(o.defaulted);
        colls = new HashMap<>(o.colls);
        bounded = new HashSet<>(o.bounded);
        views = new HashMap<>(o.views);
        viewTypes = new HashMap<>(o.viewTypes);
        exactly = new HashMap<>(o.exactly);
        stage = o.stage;
        self = o.self;
        body = o.body;
        stmts = o.stmts;
        next = o.next;
        steps = o.steps;
        thrown = o.thrown;
        caught = o.caught;
        completed = o.completed;
        pendingArgs = o.pendingArgs;
        pendingThis = o.pendingThis;
    }

    SymState copy() { return new SymState(this); }

    SymValue local(String name) { return locals.get(name); }
    void set(String name, SymValue v) { locals.put(name, v); }

    SymValue field(SymValue object, String name) {
        Map<String, SymValue> f = heap.get(object.id);
        return f == null ? null : f.get(name);
    }

    void putField(SymValue object, String name, SymValue v) {
        heap.computeIfAbsent(object.id, k -> new HashMap<>()).put(name, v);
    }

    /** The collection as it is on this path. */
    SymValue.CollInfo coll(SymValue c) {
        SymValue.CollInfo i = colls.get(c.id);
        return i == null ? c.coll : i;
    }

    /** The array's contents on this path. */
    SymValue.ArrayInfo contents(SymValue array) {
        ArrayExpr<?, ?> d = arrays.get(array.id);
        return d == null ? array.array : array.array.withData(d);
    }

    int depth() { return frames.size(); }

    boolean inlining(String signature) {
        for (Frame f : frames) if (f.calleeSignature().equals(signature)) return true;
        return false;
    }
}
