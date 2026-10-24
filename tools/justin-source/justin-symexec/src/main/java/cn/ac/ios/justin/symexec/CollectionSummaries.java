package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.symexec.SymValue.CollInfo;
import cn.ac.ios.justin.symexec.SymValue.CollInfo.Shape;
import cn.ac.ios.justin.symexec.SymValue.ElemLink;
import cn.ac.ios.justin.symexec.SymValue.Kind;
import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BitVecSort;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import com.microsoft.z3.Sort;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The collections of java.util as the executor sees them: a symbolic size and, per position in the order of
 * iteration, an element (for a map a key and a value). Enough for the conditions code usually puts on a
 * collection: whether it is empty, how many elements it has, what an iteration meets, whether it contains a
 * value, what a map holds for a key.
 *
 * <p>The type of the elements comes from the type arguments of a parameter when it has them. Otherwise it is
 * found out when the code shows it: a cast of an element that was taken out, a string that is looked up or
 * added. Until then elements are objects without contents, and an input gets whatever the value generator
 * makes for them.
 *
 * <p>Operations that look at every element (contains, a map lookup) are written out for the first
 * {@code bound} positions, and the size is bounded accordingly on the paths that use them. Operations without
 * a model that may change the collection leave it with an unknown size and unknown elements.
 */
final class CollectionSummaries {
    /** Result of a modelled call that returns nothing of interest. */
    static final SymValue DONE = SymValue.unknown();

    private static final Set<String> READ_ONLY = Set.of("equals", "hashCode", "toString", "getClass", "stream", "parallelStream", "forEach", "toArray", "containsAll",
            "subList", "spliterator", "containsValue", "lastIndexOf", "descendingIterator", "headSet", "tailSet", "comparator", "wait", "notify", "notifyAll",
            "hasPrevious", "nextIndex", "previousIndex", "capacity", "elements", "copyOf");

    private final SymbolicExecutor ex;
    private final Context ctx;
    private final int bound;
    private int fresh;

    CollectionSummaries(SymbolicExecutor ex, int bound) {
        this.ex = ex;
        this.ctx = ex.ctx();
        this.bound = Math.max(1, bound);
    }

    /** The shape the executor gives a declared type, or null when it has no model for it (sorted and concurrent collections, project classes). */
    static Shape shapeOf(String type) {
        switch (type) {
            case "java.util.List": case "java.util.Collection": case "java.lang.Iterable": case "java.util.ArrayList": case "java.util.AbstractList":
            case "java.util.AbstractCollection": case "java.util.LinkedList": case "java.util.Queue": case "java.util.Deque": case "java.util.ArrayDeque":
            case "java.util.Vector": case "java.util.Stack": case "java.util.AbstractSequentialList":
                return Shape.LIST;
            case "java.util.Set": case "java.util.HashSet": case "java.util.LinkedHashSet": case "java.util.AbstractSet":
            case "java.util.SortedSet": case "java.util.NavigableSet": case "java.util.TreeSet":
                return Shape.SET;
            case "java.util.Map": case "java.util.HashMap": case "java.util.LinkedHashMap": case "java.util.AbstractMap":
            case "java.util.SortedMap": case "java.util.NavigableMap": case "java.util.TreeMap":
                return Shape.MAP;
            default:
                return null;
        }
    }

    /** Sets and maps that keep their elements (keys) in ascending order. */
    static boolean sortedType(String type) {
        switch (type) {
            case "java.util.SortedSet": case "java.util.NavigableSet": case "java.util.TreeSet":
            case "java.util.SortedMap": case "java.util.NavigableMap": case "java.util.TreeMap":
                return true;
            default:
                return false;
        }
    }

    /** How elements of a class are modelled, or null when they are objects without contents. */
    static Kind kindOfElement(String type) {
        if (type == null) return null;
        switch (type) {
            case "java.lang.String": case "java.lang.CharSequence": return Kind.STR;
            case "java.lang.Integer": case "java.lang.Short": case "java.lang.Byte": case "java.lang.Character": case "java.lang.Boolean": return Kind.BV32;
            case "java.lang.Long": return Kind.BV64;
            case "java.lang.Double": case "java.lang.Float": return Kind.REAL;
            default: return null;
        }
    }

    private Sort sortOf(Kind k) {
        switch (k) {
            case STR: return ctx.getStringSort();
            case BV64: return ctx.mkBitVecSort(64);
            case REAL: return ctx.getRealSort();
            default: return ctx.mkBitVecSort(32);
        }
    }

    /** The elements (values) a collection has before the path stores anything: one array per collection and kind, the same on every path. */
    ArrayExpr<?, ?> origin(int id, Kind k, boolean values) {
        return ctx.mkArrayConst("c" + id + (values ? "_v_" : "_") + k, ctx.mkBitVecSort(32), sortOf(k));
    }

    // ------------------------------------------------------------------ creation

    /** A collection that comes from outside: a parameter, or a field as it is on entry. */
    SymValue symbol(List<BoolExpr> sink, String base, String type, TypeRef declared, BoolExpr nullFlag, String entryPath) {
        Shape shape = shapeOf(type);
        BitVecExpr size = ctx.mkBVConst(base + "_size", 32);
        sink.add(ctx.mkBVSGE(size, ex.bv32(0)));
        // a parameter is built by the test, element by element; a field holds what the receiver holds
        if (entryPath == null) sink.add(ctx.mkBVSLE(size, ex.bv32(bound)));
        return SymValue.collection(type, nullFlag, entryPath, false, id -> {
            CollInfo info = CollInfo.of(shape, size, sortedType(type));
            if (declared instanceof TypeRef.ClassRef c && !c.typeArgs().isEmpty()) {
                String e = c.typeArgs().get(0).erasureName();
                Kind k = kindOfElement(e);
                if (k != null) info = typeElements(id, info, k, e, sink, entryPath == null);
                if (shape == Shape.MAP && c.typeArgs().size() > 1) {
                    String v = c.typeArgs().get(1).erasureName();
                    Kind vk = kindOfElement(v);
                    if (vk != null) info = typeValues(id, info, vk, v, sink);
                }
            }
            return info;
        });
    }

    /** A collection made by the code under execution: empty, and of exactly this class. */
    SymValue created(String type) {
        Shape shape = shapeOf(type);
        return SymValue.collection(type, null, null, true, id -> CollInfo.of(shape, ex.bv32(0), sortedType(type)));
    }

    private CollInfo typeElements(int id, CollInfo info, Kind k, String type, List<BoolExpr> sink, boolean isBounded) {
        ArrayExpr<?, ?> data = origin(id, k, false);
        CollInfo typed = info.elements(k, type, data);
        ranges(typed.data, type, sink);
        // the elements of a set and the keys of a map differ from one another; sorted ones ascend
        if ((info.shape == Shape.SET || info.shape == Shape.MAP) && isBounded) {
            if (info.sorted) {
                // the order of strings is expensive for the solver: a sorted set of strings holds few of them
                int limit = k == Kind.STR ? Math.min(bound, 3) : bound;
                if (limit < bound) sink.add(ctx.mkBVSLE(info.size, ex.bv32(limit)));
                for (int i = 0; i + 1 < limit; i++) {
                    BoolExpr lt = less(select(data, ex.bv32(i)), select(data, ex.bv32(i + 1)), k);
                    if (lt != null) sink.add(ctx.mkImplies(ctx.mkBVSLT(ex.bv32(i + 1), info.size), lt));
                }
            } else {
                for (int i = 0; i < bound; i++) {
                    for (int j = i + 1; j < bound; j++) {
                        sink.add(ctx.mkImplies(ctx.mkBVSLT(ex.bv32(j), info.size), ctx.mkNot(ctx.mkEq(select(data, ex.bv32(i)), select(data, ex.bv32(j))))));
                    }
                }
            }
        }
        return typed;
    }

    private CollInfo typeValues(int id, CollInfo info, Kind k, String type, List<BoolExpr> sink) {
        CollInfo typed = info.values(k, type, origin(id, k, true));
        ranges(typed.values, type, sink);
        return typed;
    }

    /** Elements of the narrow types stay in their range. */
    private void ranges(ArrayExpr<?, ?> data, String type, List<BoolExpr> sink) {
        long lo, hi;
        switch (type) {
            case "java.lang.Boolean": lo = 0; hi = 1; break;
            case "java.lang.Character": lo = 0; hi = 0xFFFF; break;
            case "java.lang.Byte": lo = -128; hi = 127; break;
            case "java.lang.Short": lo = -32768; hi = 32767; break;
            default: return;
        }
        for (int i = 0; i < bound; i++) {
            BitVecExpr e = (BitVecExpr) select(data, ex.bv32(i));
            sink.add(ctx.mkBVSGE(e, ex.bv32(lo)));
            sink.add(ctx.mkBVSLE(e, ex.bv32(hi)));
        }
    }

    /** a < b in the order of the type, or null for an order the executor does not model. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BoolExpr less(Expr<?> a, Expr<?> b, Kind k) {
        switch (k) {
            case STR: return ctx.MkStringLt((Expr) a, (Expr) b);
            case BV32: case BV64: return ctx.mkBVSLT((BitVecExpr) a, (BitVecExpr) b);
            case REAL: return ctx.mkLt((Expr) a, (Expr) b);
            default: return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expr<?> select(ArrayExpr<?, ?> data, BitVecExpr index) {
        return ctx.mkSelect((ArrayExpr) data, (Expr) index);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArrayExpr<?, ?> store(ArrayExpr<?, ?> data, BitVecExpr index, Expr<?> value) {
        return ctx.mkStore((ArrayExpr) data, (Expr) index, (Expr) value);
    }

    private static SymValue typed(Expr<?> e, Kind k) {
        switch (k) {
            case STR: return SymValue.str(e, null);
            case BV64: return SymValue.bv64(e);
            case REAL: return SymValue.real(e);
            default: return SymValue.bv32(e);
        }
    }

    /** The class of the elements a value says its collection holds, or null when the value does not say. */
    private static String classOf(SymValue x) {
        if (x == null || x.expr == null) return null;
        if (x.kind == Kind.STR) return "java.lang.String";
        return x.refType != null && kindOfElement(x.refType) == x.kind ? x.refType : null;
    }

    private SymValue bool(BoolExpr b) { return SymValue.bv32(ctx.mkITE(b, ex.bv32(1), ex.bv32(0))); }

    /** The collection behind a view or an iterator, the value itself otherwise. */
    private static SymValue root(SymValue holder, CollInfo info) { return info.source != null ? info.source : holder; }

    private void bounded(SymState s, SymValue root, CollInfo info) {
        if (s.bounded.add(root.id)) s.pc.add(ctx.mkBVSLE(info.size, ex.bv32(bound)));
    }

    /** Operations without a model may have changed anything: size and elements are unknown from here on. */
    private void havoc(SymState s, SymValue holder, CollInfo info) {
        SymValue r = root(holder, info);
        CollInfo ri = s.coll(r);
        if (ri == null) return;
        int n = fresh++;
        BitVecExpr size = ctx.mkBVConst("h" + r.id + "_" + n + "_size", 32);
        s.pc.add(ctx.mkBVSGE(size, ex.bv32(0)));
        CollInfo h = ri.withSize(size);
        if (ri.typed()) h = h.withData(ctx.mkArrayConst("h" + r.id + "_" + n, ctx.mkBitVecSort(32), sortOf(ri.elem)));
        if (ri.valuesTyped()) h = h.withValues(ctx.mkArrayConst("h" + r.id + "_" + n + "_v", ctx.mkBitVecSort(32), sortOf(ri.val)));
        s.colls.put(r.id, h);
        s.bounded.remove(r.id);
        ex.note(s, "collection changed by a call without a model");
    }

    // ------------------------------------------------------------------ elements

    /** The element at a position of what holder shows (a collection, a view, an iterator). */
    private SymValue elementAt(SymState s, SymValue holder, CollInfo info, BitVecExpr index, int position) {
        SymValue r = root(holder, info);
        CollInfo ri = s.coll(r);
        if (info.entries) {
            return SymValue.collection("java.util.Map$Entry", null, null, false, id -> ri.view(Shape.ENTRY, r, ElemLink.Side.ELEMENT, false).withCursor(position));
        }
        boolean values = info.side == ElemLink.Side.VALUE;
        if (values ? ri.valuesTyped() : ri.typed()) return typed(select(values ? ri.values : ri.data, index), values ? ri.val : ri.elem);
        String type = values ? ri.valType : ri.elemType;
        return SymValue.element(type == null ? "java.lang.Object" : type, new ElemLink(r, info.side, index, null));
    }

    /** An element of unknown type is cast: the collection it came from holds elements of that type. */
    SymValue cast(SymState s, SymValue v, String to) {
        ElemLink l = v.link;
        Kind k = kindOfElement(to);
        if (l == null || k == null) return v;
        CollInfo ri = s.coll(l.owner);
        if (ri == null) return v;
        if (l.side == ElemLink.Side.ELEMENT) {
            if (!ri.typed()) {
                ri = typeElements(l.owner.id, ri, k, to, s.pc, s.bounded.contains(l.owner.id) || l.owner.entryPath == null && !l.owner.exactType);
                s.colls.put(l.owner.id, ri);
            } else if (ri.elem != k) {
                return ex.fresh(s, k);
            }
            return l.index == null ? v : typed(select(ri.data, l.index), k);
        }
        if (!ri.valuesTyped()) {
            ri = typeValues(l.owner.id, ri, k, to, s.pc);
            s.colls.put(l.owner.id, ri);
        } else if (ri.val != k) {
            return ex.fresh(s, k);
        }
        if (l.index != null) return typed(select(ri.values, l.index), k);
        if (l.key == null || !ri.typed() || l.key.kind != ri.elem) return ex.fresh(s, k);
        return lookup(s, l.owner, ri, l.key);
    }

    /** position i holds x, for the positions below the size. */
    private List<BoolExpr> hits(CollInfo info, ArrayExpr<?, ?> data, SymValue x) {
        List<BoolExpr> out = new ArrayList<>();
        for (int i = 0; i < bound; i++) {
            out.add(ctx.mkAnd(ctx.mkBVSLT(ex.bv32(i), info.size), ctx.mkEq(select(data, ex.bv32(i)), x.expr)));
        }
        return out;
    }

    private BoolExpr any(List<BoolExpr> hits) { return ctx.mkOr(hits.toArray(new BoolExpr[0])); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expr<?> zero(Kind k) {
        switch (k) {
            case STR: return ctx.mkString("");
            case BV64: return ctx.mkBV(0, 64);
            case REAL: return ctx.mkReal(0);
            default: return ex.bv32(0);
        }
    }

    /** What a map holds for a key: the value at the position of the key, null when no position has it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private SymValue lookup(SymState s, SymValue map, CollInfo info, SymValue key) {
        bounded(s, map, info);
        List<BoolExpr> hits = hits(info, info.data, key);
        Expr value = zero(info.val);
        for (int i = bound - 1; i >= 0; i--) value = ctx.mkITE(hits.get(i), (Expr) select(info.values, ex.bv32(i)), value);
        return typed(value, info.val).orNull(ctx.mkNot(any(hits)));
    }

    /** The collection typed by the kind of x when it is not typed yet and x tells the type; null when x cannot be compared with the elements. */
    private CollInfo comparable(SymState s, SymValue holder, CollInfo info, SymValue x) {
        if (x == null || x.expr == null) return null;
        SymValue r = root(holder, info);
        CollInfo ri = s.coll(r);
        boolean values = info.side == ElemLink.Side.VALUE;
        if (values) return ri.valuesTyped() && ri.val == x.kind ? ri : null;
        if (ri.typed()) return ri.elem == x.kind ? ri : null;
        // a string says what the elements are, a number only when it was boxed in sight
        String type = classOf(x);
        if (type == null) return null;
        ri = typeElements(r.id, ri, x.kind, type, s.pc, s.bounded.contains(r.id) || r.entryPath == null && !r.exactType);
        s.colls.put(r.id, ri);
        return ri;
    }

    // ------------------------------------------------------------------ calls

    /**
     * The result of a call on a collection, an iterator or an entry, or of a static factory; null when the call
     * has no model (the executor then goes on as for any other call), DONE when it has one and returns nothing.
     */
    SymValue apply(SymState s, String owner, String name, SymValue base, List<SymValue> args, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        if (base == null) return factory(s, owner, name, args);
        if (!base.isCollection()) return null;
        CollInfo info = s.coll(base);
        switch (info.shape) {
            case ITERATOR: return iterator(s, name, base, info, args);
            case ENTRY: return entry(s, name, base, info);
            default: break;
        }
        if (name.equals("<init>")) return construct(s, base, info, args);
        boolean stack = "java.util.Stack".equals(base.refType) || owner.equals("java.util.Stack");
        SymValue r = root(base, info);
        // a view shows the collection behind it as it is now
        if (info.source != null) {
            CollInfo ri = s.coll(r);
            info = ri.view(info.shape, r, info.side, info.entries);
        }
        switch (name) {
            case "size": return args.isEmpty() ? SymValue.bv32(info.size) : null;
            case "isEmpty": case "empty": return args.isEmpty() ? bool(ctx.mkEq(info.size, ex.bv32(0))) : null;
            case "get": case "elementAt": {
                if (args.size() != 1) return null;
                if (info.shape == Shape.MAP) return mapGet(s, base, info, args.get(0), null);
                if (info.shape != Shape.LIST || args.get(0).kind != Kind.BV32) return null;
                inRange(s, args.get(0).bv(), info.size, work, "java.lang.IndexOutOfBoundsException");
                return elementAt(s, base, info, args.get(0).bv(), -1);
            }
            case "getOrDefault": return args.size() == 2 && info.shape == Shape.MAP ? mapGet(s, base, info, args.get(0), args.get(1)) : null;
            case "contains": case "containsKey": {
                if (args.size() != 1 || (info.shape == Shape.MAP) != name.equals("containsKey")) return null;
                CollInfo ri = comparable(s, base, info, args.get(0));
                if (ri == null) return null;
                bounded(s, r, ri);
                return bool(any(hits(ri, info.side == ElemLink.Side.VALUE ? ri.values : ri.data, args.get(0))));
            }
            case "indexOf": {
                if (args.size() != 1 || info.shape != Shape.LIST) return null;
                CollInfo ri = comparable(s, base, info, args.get(0));
                if (ri == null) return null;
                bounded(s, r, ri);
                List<BoolExpr> hits = hits(ri, info.side == ElemLink.Side.VALUE ? ri.values : ri.data, args.get(0));
                Expr<BitVecSort> at = ex.bv32(-1);
                for (int i = bound - 1; i >= 0; i--) at = ctx.mkITE(hits.get(i), ex.bv32(i), at);
                return SymValue.bv32(at);
            }
            case "add": case "addLast": case "offer": case "offerLast": case "addElement": case "push": {
                if (args.size() != 1 || info.shape == Shape.MAP || info.source != null || info.sorted) break;   // a sorted set places the element by its order
                if (name.equals("push") && !stack) break;   // a deque pushes at its head
                return add(s, base, info, args.get(0), name.equals("push") ? args.get(0) : name.equals("addLast") || name.equals("addElement") ? DONE : bool(ctx.mkTrue()),
                        as, stmts, work);
            }
            case "put": {
                if (args.size() != 2 || info.shape != Shape.MAP || info.sorted) break;
                SymValue done = put(s, base, info, args.get(0), args.get(1), as, stmts, work);
                if (done != null) return done;
                break;
            }
            case "set": {
                if (args.size() != 2 || info.shape != Shape.LIST || args.get(0).kind != Kind.BV32 || info.source != null) break;
                inRange(s, args.get(0).bv(), info.size, work, "java.lang.IndexOutOfBoundsException");
                SymValue old = elementAt(s, base, info, args.get(0).bv(), -1);
                SymValue x = args.get(1);
                if (info.typed() && x.expr != null && x.kind == info.elem) {
                    s.colls.put(base.id, info.withData(store(info.data, args.get(0).bv(), x.expr)));
                    return old;
                }
                break;
            }
            case "remove": {
                if (info.source != null) break;
                if (args.size() == 1 && info.shape == Shape.LIST && args.get(0).kind == Kind.BV32 && args.get(0).refType == null) {
                    // remove(int index): the elements above the position move down
                    inRange(s, args.get(0).bv(), info.size, work, "java.lang.IndexOutOfBoundsException");
                    SymValue old = elementAt(s, base, info, args.get(0).bv(), -1);
                    s.colls.put(base.id, without(s, base, info, args.get(0).bv()));
                    return old;
                }
                if (args.size() == 1 && info.shape != Shape.MAP && !info.sorted) {
                    CollInfo ci = comparable(s, base, info, args.get(0));
                    if (ci == null) break;
                    bounded(s, base, ci);
                    List<BoolExpr> hits = hits(ci, ci.data, args.get(0));
                    Expr<BitVecSort> at = ex.bv32(-1);
                    for (int i = bound - 1; i >= 0; i--) at = ctx.mkITE(hits.get(i), ex.bv32(i), at);
                    BitVecExpr index = (BitVecExpr) at;
                    // the element is there: it goes, and true comes back; otherwise nothing changes
                    fork(s, any(hits), o -> o.colls.put(base.id, without(o, base, ci, index)), bool(ctx.mkTrue()), as, stmts, work);
                    return bool(ctx.mkFalse());
                }
                break;
            }
            case "addAll": {
                if (args.size() != 1 || info.shape != Shape.LIST || info.source != null || args.get(0) == null || !args.get(0).isCollection()) break;
                SymValue from = args.get(0);
                CollInfo fi = s.coll(from);
                if (fi.source != null) fi = s.coll(fi.source).view(fi.shape, fi.source, fi.side, fi.entries);
                if (fi.shape == Shape.MAP || fi.entries) break;
                SymValue fr = root(from, fi);
                bounded(s, fr, fi);
                BitVecExpr more = ctx.mkBVAdd(info.size, fi.size);
                if (info.typed() && fi.typed() && info.elem == fi.elem) {
                    ArrayExpr<?, ?> data = info.data;
                    for (int j = 0; j < bound; j++) data = store(data, ctx.mkBVAdd(info.size, ex.bv32(j)), select(fi.data, ex.bv32(j)));
                    s.colls.put(base.id, info.withData(data).withSize(more));
                } else if (!info.typed() && !fi.typed()) {
                    s.colls.put(base.id, info.withSize(more));
                } else {
                    break;
                }
                return bool(ctx.mkBVSGT(fi.size, ex.bv32(0)));
            }
            case "containsAll": {
                if (args.size() != 1 || args.get(0) == null || !args.get(0).isCollection()) break;
                SymValue from = args.get(0);
                CollInfo fi = s.coll(from);
                if (fi.source != null) fi = s.coll(fi.source).view(fi.shape, fi.source, fi.side, fi.entries);
                if (fi.shape == Shape.MAP || fi.entries || !fi.typed()) break;
                CollInfo ci = comparable(s, base, info, typed(select(fi.data, ex.bv32(0)), fi.elem));
                if (ci == null) break;
                bounded(s, r, ci);
                bounded(s, root(from, fi), fi);
                List<BoolExpr> all = new ArrayList<>();
                for (int j = 0; j < bound; j++) {
                    all.add(ctx.mkImplies(ctx.mkBVSLT(ex.bv32(j), fi.size), any(hits(ci, ci.data, typed(select(fi.data, ex.bv32(j)), fi.elem)))));
                }
                return bool(ctx.mkAnd(all.toArray(new BoolExpr[0])));
            }
            case "first": case "last": case "firstKey": case "lastKey": {
                if (!args.isEmpty() || !info.sorted) break;
                s.pc.add(ctx.mkBVSGT(info.size, ex.bv32(0)));   // empty: NoSuchElementException, not followed
                return elementAt(s, base, info, name.startsWith("first") ? ex.bv32(0) : ctx.mkBVSub(info.size, ex.bv32(1)), -1);
            }
            case "clear": case "removeAllElements": {
                if (!args.isEmpty() || info.source != null) break;
                s.colls.put(base.id, info.withSize(ex.bv32(0)));
                s.bounded.add(base.id);
                return DONE;
            }
            case "iterator": case "listIterator": case "elements": {
                if (!args.isEmpty()) return null;
                CollInfo it = info.view(Shape.ITERATOR, r, info.side, info.entries);
                return SymValue.collection("java.util.Iterator", null, null, false, id -> it);
            }
            case "keySet": case "values": case "entrySet": {
                if (!args.isEmpty() || info.shape != Shape.MAP) return null;
                CollInfo view = name.equals("values") ? info.view(Shape.LIST, base, ElemLink.Side.VALUE, false)
                        : info.view(Shape.SET, base, ElemLink.Side.ELEMENT, name.equals("entrySet"));
                return SymValue.collection(name.equals("values") ? "java.util.Collection" : "java.util.Set", null, null, false, id -> view);
            }
            case "getFirst": case "peekFirst": case "element": case "firstElement": case "getLast": case "peekLast": case "lastElement": case "peek": case "pop":
            case "removeLast": case "pollLast": {
                if (!args.isEmpty() || info.shape != Shape.LIST) break;
                boolean last = name.endsWith("Last") || name.equals("lastElement") || stack && (name.equals("peek") || name.equals("pop"));
                boolean removes = name.equals("pop") || name.equals("removeLast") || name.equals("pollLast");
                if (removes && (!last || info.source != null)) break;   // taking from the head moves every element
                // the collection is not empty on the path that goes on (the empty case throws or returns null: not followed)
                s.pc.add(ctx.mkBVSGT(info.size, ex.bv32(0)));
                BitVecExpr at = last ? ctx.mkBVSub(info.size, ex.bv32(1)) : ex.bv32(0);
                SymValue e = elementAt(s, base, info, at, -1);
                if (removes) s.colls.put(base.id, info.withSize(ctx.mkBVSub(info.size, ex.bv32(1))));
                return e;
            }
            default:
                break;
        }
        if (READ_ONLY.contains(name)) return null;
        havoc(s, base, info);
        return null;
    }

    /** The list without the element at the index: what is above it moves down one position. */
    private CollInfo without(SymState s, SymValue base, CollInfo info, BitVecExpr index) {
        BitVecExpr less = ctx.mkBVSub(info.size, ex.bv32(1));
        if (!info.typed()) return info.withSize(less);
        bounded(s, base, info);
        ArrayExpr<?, ?> data = info.data;
        for (int i = 0; i + 1 < bound; i++) {
            @SuppressWarnings({"unchecked", "rawtypes"}) Expr<?> moved = ctx.mkITE(ctx.mkBVSGE(ex.bv32(i), index), (Expr) select(info.data, ex.bv32(i + 1)), (Expr) select(info.data, ex.bv32(i)));
            data = store(data, ex.bv32(i), moved);
        }
        return info.withData(data).withSize(less);
    }

    /** The path goes on with the index in range; the other case ends with the exception. */
    private void inRange(SymState s, BitVecExpr index, BitVecExpr size, Deque<SymState> work, String exception) {
        BoolExpr in = ctx.mkAnd(ctx.mkBVSGE(index, ex.bv32(0)), ctx.mkBVSLT(index, size));
        SymState out = s.copy();
        out.pc.add(ctx.mkNot(in));
        ex.raise(out, exception);
        work.push(out);
        s.pc.add(in);
    }

    /** A second path on which cond holds, the call has the effect and returns result; s goes on with the negation. */
    private void fork(SymState s, BoolExpr cond, Consumer<SymState> effect, SymValue result, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        SymState o = s.copy();
        o.pc.add(cond);
        effect.accept(o);
        if (as != null && as.getLeftOp() instanceof Local l) o.set(l.getName(), result == null || result == DONE ? SymValue.unknown() : result);
        o.next = ex.after(stmts, as != null ? as : s.next);
        if (ex.feasible(o)) work.push(o);
        s.pc.add(ctx.mkNot(cond));
    }

    private SymValue add(SymState s, SymValue base, CollInfo info, SymValue x, SymValue result, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        BitVecExpr more = ctx.mkBVAdd(info.size, ex.bv32(1));
        CollInfo ci = x != null && x.expr != null && (info.typed() || classOf(x) != null) ? comparable(s, base, info, x) : null;
        if (ci == null) {
            if (info.typed()) { havoc(s, base, info); return result; }
            // elements without contents: the collection grows, what it holds stays unknown
            s.colls.put(base.id, info.withSize(more));
            return result;
        }
        CollInfo grown = ci.withData(store(ci.data, ci.size, x.expr)).withSize(more);
        if (info.shape == Shape.SET) {
            // a set that has the element stays as it is
            bounded(s, base, ci);
            fork(s, any(hits(ci, ci.data, x)), o -> { }, bool(ctx.mkFalse()), as, stmts, work);
        }
        s.colls.put(base.id, grown);
        return result;
    }

    private SymValue mapGet(SymState s, SymValue base, CollInfo info, SymValue key, SymValue otherwise) {
        CollInfo ri = comparable(s, base, info, key);
        if (ri == null) return null;
        if (!ri.valuesTyped()) {
            if (otherwise != null) return null;
            bounded(s, base, ri);
            // the value is there when the key is; what it is shows when it is cast
            SymValue e = SymValue.element(ri.valType == null ? "java.lang.Object" : ri.valType, new ElemLink(base, ElemLink.Side.VALUE, null, key));
            return e.orNull(ctx.mkNot(any(hits(ri, ri.data, key))));
        }
        SymValue found = lookup(s, base, ri, key);
        if (otherwise == null) return found;
        if (otherwise.expr == null || otherwise.kind != ri.val) return null;
        @SuppressWarnings({"unchecked", "rawtypes"}) Expr<?> e = ctx.mkITE(found.nullFlag, (Expr) otherwise.expr, (Expr) found.expr);
        return otherwise.nullFlag == null ? typed(e, ri.val) : typed(e, ri.val).orNull(ctx.mkAnd(found.nullFlag, otherwise.nullFlag));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SymValue put(SymState s, SymValue base, CollInfo info, SymValue key, SymValue value, JAssignStmt as, List<Stmt> stmts, Deque<SymState> work) {
        if (info.source != null) return null;
        CollInfo ri = comparable(s, base, info, key);
        if (ri == null) return null;
        boolean storesValue = value != null && value.expr != null && (ri.valuesTyped() ? ri.val == value.kind : classOf(value) != null);
        if (!ri.valuesTyped() && storesValue) {
            ri = typeValues(base.id, ri, value.kind, classOf(value), s.pc);
            s.colls.put(base.id, ri);
        }
        if (ri.valuesTyped() && !storesValue) return null;
        bounded(s, base, ri);
        List<BoolExpr> hits = hits(ri, ri.data, key);
        CollInfo now = ri;
        // the key is there: its value is replaced, the old one returned
        SymValue old = ri.valuesTyped() ? lookup(s, base, ri, key) : SymValue.element("java.lang.Object", new ElemLink(base, ElemLink.Side.VALUE, null, key));
        fork(s, any(hits), o -> {
            if (!now.valuesTyped()) return;
            ArrayExpr values = (ArrayExpr) now.values;
            for (int i = 0; i < bound; i++) values = (ArrayExpr) store(values, ex.bv32(i), ctx.mkITE(hits.get(i), (Expr) value.expr, (Expr) select(now.values, ex.bv32(i))));
            o.colls.put(base.id, now.withValues(values));
        }, ri.valuesTyped() ? typed(old.expr, ri.val) : old, as, stmts, work);
        // the key is new: one more entry
        CollInfo grown = ri.withData(store(ri.data, ri.size, key.expr)).withSize(ctx.mkBVAdd(ri.size, ex.bv32(1)));
        if (ri.valuesTyped()) grown = grown.withValues(store(ri.values, ri.size, value.expr));
        s.colls.put(base.id, grown);
        return SymValue.nullRef(ri.valType);
    }

    private SymValue iterator(SymState s, String name, SymValue base, CollInfo info, List<SymValue> args) {
        SymValue r = root(base, info);
        CollInfo ri = s.coll(r);
        switch (name) {
            case "hasNext": case "hasMoreElements":
                return bool(ctx.mkBVSLT(ex.bv32(info.cursor), ri.size));
            case "next": case "nextElement": {
                // the path that asks for an element that is not there ends with an exception: not followed
                s.pc.add(ctx.mkBVSLT(ex.bv32(info.cursor), ri.size));
                SymValue e = elementAt(s, base, info, ex.bv32(info.cursor), info.cursor);
                s.colls.put(base.id, info.withCursor(info.cursor + 1));
                return e;
            }
            case "remove": case "set": case "add":
                havoc(s, base, info);
                return null;
            default:
                return null;
        }
    }

    private SymValue entry(SymState s, String name, SymValue base, CollInfo info) {
        ElemLink.Side side;
        switch (name) {
            case "getKey": side = ElemLink.Side.ELEMENT; break;
            case "getValue": side = ElemLink.Side.VALUE; break;
            case "setValue": havoc(s, base, info); return null;
            default: return null;
        }
        CollInfo ri = s.coll(info.source);
        return elementAt(s, base, ri.view(Shape.LIST, info.source, side, false), ex.bv32(info.cursor), info.cursor);
    }

    /** new ArrayList(), new ArrayList(n), new ArrayList(collection), new HashMap(map). */
    private SymValue construct(SymState s, SymValue base, CollInfo info, List<SymValue> args) {
        if (args.size() == 1 && args.get(0) != null && args.get(0).isCollection()) {
            SymValue from = args.get(0);
            CollInfo fi = s.coll(from);
            if (fi.source != null) fi = s.coll(fi.source).view(fi.shape, fi.source, fi.side, fi.entries);
            if ((fi.shape == Shape.MAP) != (info.shape == Shape.MAP) || fi.entries || fi.shape == Shape.ITERATOR || fi.shape == Shape.ENTRY) return DONE;
            CollInfo copy = new CollInfo(info.shape, fi.size, fi.elem, fi.elemType, fi.data, fi.val, fi.valType, fi.values, null, null, 0, null, ElemLink.Side.ELEMENT, false);
            s.colls.put(base.id, copy);
            if (s.bounded.contains(root(from, fi).id)) s.bounded.add(base.id);
        }
        return DONE;
    }

    private SymValue factory(SymState s, String owner, String name, List<SymValue> args) {
        switch (owner) {
            case "java.util.Collections":
                switch (name) {
                    case "emptyList": return created("java.util.List");
                    case "emptySet": return created("java.util.Set");
                    case "emptyMap": return created("java.util.Map");
                    case "singletonList": case "singleton": return args.size() == 1 ? of(s, name.equals("singleton") ? "java.util.Set" : "java.util.List", args) : null;
                    case "unmodifiableList": case "unmodifiableCollection": case "unmodifiableSet": case "unmodifiableMap": case "synchronizedList":
                    case "synchronizedCollection": case "synchronizedSet": case "synchronizedMap":
                        return args.size() == 1 && args.get(0) != null && args.get(0).isCollection() ? args.get(0) : null;
                    default: return null;
                }
            case "java.util.Arrays": case "java.util.List": case "java.util.Set": {
                if (!name.equals("asList") && !name.equals("of")) return null;
                String type = owner.equals("java.util.Set") ? "java.util.Set" : "java.util.List";
                if (args.size() == 1 && args.get(0) != null && args.get(0).isArray()) {
                    SymValue a = args.get(0);
                    SymValue.ArrayInfo ai = s.contents(a);
                    Kind k = kindOfElement(ai.elemType);
                    if (k == null || k != ai.elem) return null;
                    return SymValue.collection(type, null, null, true, id -> CollInfo.of(shapeOf(type), ai.length).elements(k, ai.elemType, ai.data));
                }
                return of(s, type, args);
            }
            default:
                return null;
        }
    }

    /** A collection of the given elements, when they are all of one kind the executor models. */
    private SymValue of(SymState s, String made, List<SymValue> elements) {
        if (elements.isEmpty()) return created(made);
        Kind k = null;
        for (SymValue e : elements) {
            if (e == null || e.expr == null || e.nullFlag != null || k != null && e.kind != k) return null;
            k = e.kind;
        }
        String type = classOf(elements.get(0));
        if (type == null) return null;
        for (SymValue e : elements) if (!type.equals(classOf(e))) return null;
        Kind kind = k;
        return SymValue.collection(made, null, null, true, id -> {
            ArrayExpr<?, ?> data = ctx.mkArrayConst("c" + id + "_made", ctx.mkBitVecSort(32), sortOf(kind));
            for (int i = 0; i < elements.size(); i++) data = store(data, ex.bv32(i), elements.get(i).expr);
            return CollInfo.of(shapeOf(made), ex.bv32(elements.size())).elements(kind, type, data);
        });
    }

    // ------------------------------------------------------------------ receiver state and inputs

    /** The entry symbol of a collection field set to what a concrete receiver holds. */
    void constrain(SymValue sym, ReceiverStates.Elements held, List<BoolExpr> out) {
        out.add(ctx.mkEq(sym.coll.size, ex.bv32(held.size())));
        if (held.elements() == null) return;
        for (int i = 0; i < held.elements().size() && i < 64; i++) {
            Object x = held.elements().get(i);
            if (x == null) continue;
            Kind k = kindOfElement(x.getClass().getName());
            if (k == null) continue;
            Expr<?> c;
            switch (k) {
                case STR: c = ctx.mkString(SymbolicExecutor.z3Literal(x.toString())); break;
                case BV64: c = ctx.mkBV(((Number) x).longValue(), 64); break;
                case REAL: {
                    double d = ((Number) x).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    c = ctx.mkReal(new java.math.BigDecimal(d).toPlainString());
                    break;
                }
                default: c = ex.bv32(x instanceof Character ch ? ch : x instanceof Boolean b ? (b ? 1 : 0) : ((Number) x).longValue());
            }
            @SuppressWarnings({"unchecked", "rawtypes"}) BoolExpr eq = ctx.mkEq((Expr) select(origin(sym.id, k, false), ex.bv32(i)), (Expr) c);
            out.add(eq);
        }
    }

    /**
     * The collection an input has to be, from the model of a path: as many elements as the size says, the
     * elements the model gives them where they have a type, generated ones otherwise. Null when no such value
     * can be built (the generator then makes any collection).
     */
    Value value(Model model, SymState s, SymValue v, TypeRef declared, ValueSource values) {
        CollInfo init = v.coll, now = s.coll(v);
        if (init == null || init.shape == Shape.ITERATOR || init.shape == Shape.ENTRY) return null;
        TypeRef concrete = values.concrete(declared);
        if (!(concrete instanceof TypeRef.ClassRef c)) return null;
        int n = (int) ((BitVecNum) model.eval(ex.inModel(init.size), true)).getBigInteger().longValue();
        n = Math.max(0, Math.min(n, bound));
        Kind k = init.typed() ? init.elem : now.originData != null ? now.elem : Kind.UNKNOWN;
        ArrayExpr<?, ?> data = init.typed() ? init.data : now.originData;
        String et = init.typed() ? init.elemType : now.elemType;
        TypeRef elem = k != Kind.UNKNOWN && et != null ? TypeRef.ClassRef.of(et) : argument(c, 0);
        boolean distinct = init.shape != Shape.LIST;
        List<Value> elems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Value e = null;
            if (k != Kind.UNKNOWN) {
                e = ex.elementValue(model.eval(ex.inModel(select(data, ex.bv32(i))), true), k, elem);
            } else {
                for (int attempt = 0; attempt < 6 && (e == null || distinct && elems.contains(e)); attempt++) e = values.generate(elem);
            }
            if (e == null || e instanceof Value.NullValue || distinct && elems.contains(e)) return null;
            elems.add(e);
        }
        List<Value> vals = null;
        TypeRef val = null;
        if (init.shape == Shape.MAP) {
            Kind vk = init.valuesTyped() ? init.val : now.originValues != null ? now.val : Kind.UNKNOWN;
            ArrayExpr<?, ?> vdata = init.valuesTyped() ? init.values : now.originValues;
            String vt = init.valuesTyped() ? init.valType : now.valType;
            val = vk != Kind.UNKNOWN && vt != null ? TypeRef.ClassRef.of(vt) : argument(c, 1);
            vals = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Value e = vk != Kind.UNKNOWN ? ex.elementValue(model.eval(ex.inModel(select(vdata, ex.bv32(i))), true), vk, val) : values.generate(val);
                if (e == null || e instanceof Value.NullValue) return null;
                vals.add(e);
            }
        }
        return build(c, init.shape, elems, vals, elem, val);
    }

    private static TypeRef argument(TypeRef.ClassRef c, int i) {
        if (i >= c.typeArgs().size()) return TypeRef.OBJECT;
        TypeRef a = c.typeArgs().get(i);
        if (a instanceof TypeRef.Wildcard w) return w.upper() != null ? w.upper() : TypeRef.OBJECT;
        if (a instanceof TypeRef.TypeVar || a instanceof TypeRef.Primitive) return TypeRef.OBJECT;
        return a;
    }

    /** new ArrayList(), then add(e) per element: works on every Java level and keeps the order of the model. */
    private static Value build(TypeRef.ClassRef declared, Shape shape, List<Value> elems, List<Value> vals, TypeRef elem, TypeRef val) {
        String impl;
        switch (declared.binaryName()) {
            case "java.util.LinkedList": case "java.util.Vector": case "java.util.Stack": case "java.util.ArrayDeque": impl = declared.binaryName(); break;
            case "java.util.Queue": case "java.util.Deque": impl = "java.util.ArrayDeque"; break;
            case "java.util.AbstractSequentialList": impl = "java.util.LinkedList"; break;
            case "java.util.SortedSet": case "java.util.NavigableSet": case "java.util.TreeSet": impl = "java.util.TreeSet"; break;
            case "java.util.SortedMap": case "java.util.NavigableMap": case "java.util.TreeMap": impl = "java.util.TreeMap"; break;
            default: impl = shape == Shape.MAP ? "java.util.LinkedHashMap" : shape == Shape.SET ? "java.util.LinkedHashSet" : "java.util.ArrayList";
        }
        List<TypeRef> targs = new ArrayList<>();
        targs.add(usable(declared, 0, elem));
        if (shape == Shape.MAP) targs.add(usable(declared, 1, val));
        Value.Recipe empty = new Value.Recipe.ConstructorCall(impl, "()V", List.of());
        TypeRef.ClassRef type = new TypeRef.ClassRef(impl, targs);
        if (elems.isEmpty()) return new Value.ObjectValue(type, empty);
        List<Value.Call> calls = new ArrayList<>();
        for (int i = 0; i < elems.size(); i++) {
            if (shape == Shape.MAP) calls.add(new Value.Call(impl, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", List.of(boxed(elems.get(i)), boxed(vals.get(i)))));
            else calls.add(new Value.Call(impl, "add", "(Ljava/lang/Object;)Z", List.of(boxed(elems.get(i)))));
        }
        return new Value.ObjectValue(type, new Value.Recipe.Sequence(empty, calls));
    }

    /** The declared type argument where a declaration can use it as it stands, else the type of the elements. */
    private static TypeRef usable(TypeRef.ClassRef declared, int i, TypeRef fallback) {
        if (i >= declared.typeArgs().size()) return fallback;
        TypeRef a = declared.typeArgs().get(i);
        if (a instanceof TypeRef.Wildcard || a instanceof TypeRef.TypeVar || a instanceof TypeRef.Primitive) return fallback;
        return a;
    }

    private static Value boxed(Value v) {
        if (v instanceof Value.PrimitiveValue p) return new Value.ObjectValue(TypeRef.ClassRef.of(p.kind().boxed), new Value.Recipe.Boxed(p));
        return v;
    }
}
