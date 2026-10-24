package cn.ac.ios.justin.symexec;

import com.microsoft.z3.ArrayExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Expr;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A value in the symbolic state. Integral types are bit-vectors (32 or 64 bits, Java semantics),
 * floating point is approximated by reals, strings are Z3 sequences, references carry a nullness
 * flag plus either an array (Z3 array + symbolic length) or an identity under which the path's heap
 * ({@link SymState}) keeps the fields.
 */
public final class SymValue {

    public enum Kind { BV32, BV64, REAL, STR, REF, UNKNOWN }

    private static final AtomicInteger IDS = new AtomicInteger();

    public final Kind kind;
    /** The Z3 expression: BitVecExpr, ArithExpr (real), SeqExpr (string); null for REF and UNKNOWN. */
    public final Expr<?> expr;
    /** For REF (and STR) values: null means "known non-null"; otherwise a boolean that is true when the value is null. */
    public final BoolExpr nullFlag;
    /** For REF arrays: the array as it was when the value was made; the path's current contents are in the state. */
    public final ArrayInfo array;
    /** Static type name of a reference, when known (e.g. java.lang.String, int[]). */
    public final String refType;
    /**
     * Identity of an object or array (0 for everything else). Fields and array contents are kept per path under this
     * number, so two locals holding the same object see each other's writes and a fork does not.
     */
    public final int id;
    /**
     * For an object or array that is part of the receiver as it is when the method is entered: the chain of
     * fields that leads to it from the receiver ("" for the receiver itself), otherwise null. Fields read from
     * such an object before anything wrote them are entry symbols, which a concrete receiver can give values to.
     */
    public final String entryPath;
    /** True for objects whose fields the executor follows (not arrays, strings or unknown values). */
    public final boolean hasFields;
    /**
     * What is known about the class of the object: EXACT when it was created by a new expression or read off a
     * concrete receiver, otherwise the declared type is only an upper bound.
     */
    public final boolean exactType;
    /**
     * For a collection, map, iterator or map entry the executor models: what it is when the value is made. What it
     * is later on a path (after add, next, ...) is in the state, under the identity of the value.
     */
    CollInfo coll;
    /** For an element taken out of a collection whose element type is not known yet: where it came from. */
    ElemLink link;

    /** Where an element of unknown type came from; a cast tells the type, and the collection is typed by it. */
    public static final class ElemLink {
        public enum Side { ELEMENT, VALUE }
        public final SymValue owner;
        public final Side side;
        /** Position in the collection, or null for the value a map holds for key. */
        public final BitVecExpr index;
        public final SymValue key;
        public ElemLink(SymValue owner, Side side, BitVecExpr index, SymValue key) {
            this.owner = owner; this.side = side; this.index = index; this.key = key;
        }
    }

    /**
     * A collection as the executor sees it: a size and, per position, an element (for a map a key and a value),
     * in the order of iteration. Immutable; an operation that changes the collection puts a changed copy into
     * the state.
     */
    public static final class CollInfo {
        public enum Shape { LIST, SET, MAP, ITERATOR, ENTRY }
        public final Shape shape;
        public final BitVecExpr size;
        /** Kind.UNKNOWN while the type of the elements (keys) is not known; data is null then. */
        public final Kind elem;
        public final String elemType;
        public final ArrayExpr<?, ?> data;
        /** Maps: the values, as the elements. */
        public final Kind val;
        public final String valType;
        public final ArrayExpr<?, ?> values;
        /** Elements and values as they were when the collection was typed (before anything was stored): what an input has to hold. */
        public final ArrayExpr<?, ?> originData, originValues;
        /** Iterators and entries: position; views and iterators: the collection behind them and which side of it they show. */
        public final int cursor;
        public final SymValue source;
        public final ElemLink.Side side;
        /** A view of the entries of a map: its elements are entries. */
        public final boolean entries;
        /** A sorted set or map: the elements (keys) are in ascending order. */
        public final boolean sorted;

        public CollInfo(Shape shape, BitVecExpr size, Kind elem, String elemType, ArrayExpr<?, ?> data, Kind val, String valType, ArrayExpr<?, ?> values,
                        ArrayExpr<?, ?> originData, ArrayExpr<?, ?> originValues, int cursor, SymValue source, ElemLink.Side side, boolean entries) {
            this(shape, size, elem, elemType, data, val, valType, values, originData, originValues, cursor, source, side, entries, false);
        }

        public CollInfo(Shape shape, BitVecExpr size, Kind elem, String elemType, ArrayExpr<?, ?> data, Kind val, String valType, ArrayExpr<?, ?> values,
                        ArrayExpr<?, ?> originData, ArrayExpr<?, ?> originValues, int cursor, SymValue source, ElemLink.Side side, boolean entries, boolean sorted) {
            this.shape = shape; this.size = size; this.elem = elem; this.elemType = elemType; this.data = data; this.val = val; this.valType = valType;
            this.values = values; this.originData = originData; this.originValues = originValues; this.cursor = cursor; this.source = source; this.side = side;
            this.entries = entries; this.sorted = sorted;
        }

        public static CollInfo of(Shape shape, BitVecExpr size) {
            return new CollInfo(shape, size, Kind.UNKNOWN, null, null, Kind.UNKNOWN, null, null, null, null, 0, null, ElemLink.Side.ELEMENT, false);
        }

        public static CollInfo of(Shape shape, BitVecExpr size, boolean sorted) {
            return new CollInfo(shape, size, Kind.UNKNOWN, null, null, Kind.UNKNOWN, null, null, null, null, 0, null, ElemLink.Side.ELEMENT, false, sorted);
        }

        public boolean typed() { return elem != Kind.UNKNOWN && data != null; }
        public boolean valuesTyped() { return val != Kind.UNKNOWN && values != null; }

        public CollInfo withSize(BitVecExpr s) { return new CollInfo(shape, s, elem, elemType, data, val, valType, values, originData, originValues, cursor, source, side, entries, sorted); }
        public CollInfo withData(ArrayExpr<?, ?> d) { return new CollInfo(shape, size, elem, elemType, d, val, valType, values, originData, originValues, cursor, source, side, entries, sorted); }
        public CollInfo withValues(ArrayExpr<?, ?> v) { return new CollInfo(shape, size, elem, elemType, data, val, valType, v, originData, originValues, cursor, source, side, entries, sorted); }
        public CollInfo withCursor(int c) { return new CollInfo(shape, size, elem, elemType, data, val, valType, values, originData, originValues, c, source, side, entries, sorted); }
        public CollInfo elements(Kind k, String type, ArrayExpr<?, ?> d) {
            return new CollInfo(shape, size, k, type, d, val, valType, values, originData == null ? d : originData, originValues, cursor, source, side, entries, sorted);
        }
        public CollInfo values(Kind k, String type, ArrayExpr<?, ?> v) {
            return new CollInfo(shape, size, elem, elemType, data, k, type, v, originData, originValues == null ? v : originValues, cursor, source, side, entries, sorted);
        }
        public CollInfo view(Shape as, SymValue of, ElemLink.Side which, boolean ofEntries) {
            boolean v = which == ElemLink.Side.VALUE;
            return new CollInfo(as, size, v ? val : elem, v ? valType : elemType, v ? values : data, Kind.UNKNOWN, null, null, null, null, 0, of, which, ofEntries, sorted && !v);
        }
    }

    public static final class ArrayInfo {
        public final ArrayExpr<?, ?> data;
        public final BitVecExpr length;
        public final Kind elem;
        public final String elemType;
        public ArrayInfo(ArrayExpr<?, ?> data, BitVecExpr length, Kind elem, String elemType) {
            this.data = data; this.length = length; this.elem = elem; this.elemType = elemType;
        }
        public ArrayInfo withData(ArrayExpr<?, ?> d) { return new ArrayInfo(d, length, elem, elemType); }
    }

    private SymValue(Kind kind, Expr<?> expr, BoolExpr nullFlag, ArrayInfo array, boolean hasFields, String refType, int id, String entryPath, boolean exactType) {
        this.kind = kind; this.expr = expr; this.nullFlag = nullFlag; this.array = array; this.hasFields = hasFields; this.refType = refType;
        this.id = id; this.entryPath = entryPath; this.exactType = exactType;
    }

    public static SymValue bv32(Expr<?> e) { return new SymValue(Kind.BV32, e, null, null, false, null, 0, null, false); }
    public static SymValue bv64(Expr<?> e) { return new SymValue(Kind.BV64, e, null, null, false, null, 0, null, false); }
    public static SymValue real(Expr<?> e) { return new SymValue(Kind.REAL, e, null, null, false, null, 0, null, false); }
    public static SymValue str(Expr<?> seq, BoolExpr nullFlag) { return new SymValue(Kind.STR, seq, nullFlag, null, false, "java.lang.String", 0, null, false); }
    public static SymValue nullRef(String type) { return new SymValue(Kind.REF, null, null, null, false, type == null ? "null" : type, 0, null, false).markNull(); }
    public static SymValue object(String type, BoolExpr nullFlag) { return new SymValue(Kind.REF, null, nullFlag, null, true, type, IDS.incrementAndGet(), null, false); }
    /** An object made by a new expression: its class is exactly the named one. */
    public static SymValue created(String type) { return new SymValue(Kind.REF, null, null, null, true, type, IDS.incrementAndGet(), null, true); }
    public static SymValue array(ArrayInfo info, BoolExpr nullFlag, String type) { return new SymValue(Kind.REF, null, nullFlag, info, false, type, IDS.incrementAndGet(), null, false); }
    /** A collection (see CollInfo); info gets the identity of the new value, which names the symbols of its elements. */
    public static SymValue collection(String type, BoolExpr nullFlag, String entryPath, boolean exact, java.util.function.IntFunction<CollInfo> info) {
        SymValue v = new SymValue(Kind.REF, null, nullFlag, null, false, type, IDS.incrementAndGet(), entryPath, exact);
        v.coll = info.apply(v.id);
        return v;
    }

    /** An element whose type is not known yet. */
    public static SymValue element(String type, ElemLink link) {
        SymValue v = new SymValue(Kind.REF, null, null, null, false, type, IDS.incrementAndGet(), null, false);
        v.link = link;
        return v;
    }

    /** A number or string that may be null: what a map returns for a key, a boxed value read from a collection. */
    public SymValue orNull(BoolExpr flag) {
        SymValue v = new SymValue(kind, expr, flag, array, hasFields, refType, id, entryPath, exactType);
        v.coll = coll; v.link = link;
        return v;
    }

    /** The same number, known to be the content of a boxed value of this class (Integer.valueOf(n)). */
    public SymValue boxedAs(String type) {
        SymValue v = new SymValue(kind, expr, nullFlag, array, hasFields, type, id, entryPath, exactType);
        v.coll = coll; v.link = link;
        return v;
    }

    public static SymValue unknown() { return new SymValue(Kind.UNKNOWN, null, null, null, false, null, 0, null, false); }

    /** The receiver, or an object reached from it through fields, as it is on entry. */
    public static SymValue entryObject(String type, BoolExpr nullFlag, String entryPath) {
        return new SymValue(Kind.REF, null, nullFlag, null, true, type, IDS.incrementAndGet(), entryPath, false);
    }

    public static SymValue entryArray(ArrayInfo info, BoolExpr nullFlag, String type, String entryPath) {
        return new SymValue(Kind.REF, null, nullFlag, info, false, type, IDS.incrementAndGet(), entryPath, false);
    }

    private boolean definitelyNull;

    private SymValue markNull() { this.definitelyNull = true; return this; }

    public boolean isDefinitelyNull() { return definitelyNull; }
    public boolean isDefinitelyNonNull() { return !definitelyNull && nullFlag == null && (kind == Kind.REF || kind == Kind.STR); }
    public boolean isNumeric() { return kind == Kind.BV32 || kind == Kind.BV64 || kind == Kind.REAL; }
    public boolean isArray() { return array != null; }
    public BitVecExpr bv() { return (BitVecExpr) expr; }

    /** The same array (same identity) with other contents; used where the contents are tracked in the value itself. */
    public SymValue withArray(ArrayInfo info) { return new SymValue(kind, expr, nullFlag, info, hasFields, refType, id, entryPath, exactType); }

    public boolean isCollection() { return coll != null; }

    /** The same reference, known not to be null (after a null check went the other way). */
    public SymValue nonNull() {
        SymValue v = new SymValue(kind, expr, null, array, hasFields, refType, id, entryPath, exactType);
        v.coll = coll; v.link = link;
        return v;
    }

    @Override public String toString() {
        return kind + (expr != null ? "(" + expr + ")" : "") + (definitelyNull ? "=null" : nullFlag != null ? "?null" : "");
    }
}
