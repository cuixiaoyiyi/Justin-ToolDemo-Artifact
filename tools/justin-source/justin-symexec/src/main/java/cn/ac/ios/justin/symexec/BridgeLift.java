package cn.ac.ios.justin.symexec;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BitVecSort;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.IntSort;
import com.microsoft.z3.enumerations.Z3_decl_kind;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes the conversions between bit-vectors and integers off the string terms of a path condition.
 *
 * <p>Java {@code int} values are 32-bit vectors in the executor, string lengths and indices are integers in the
 * solver's theory of sequences, so every {@code s.length()}, {@code s.charAt(i)}, {@code s.indexOf(..)} puts an
 * {@code int_to_bv} or {@code ubv_to_int} between the two. Measured on 69280 solver calls of Justin (StringTime,
 * bridge-study): with no such conversion on a string term under 1% of the calls take 200 ms or more however many
 * string operations there are; with one, two, three or more the share is 8%, 38%, 84%.
 *
 * <p>The rewrite: a path condition without a conversion is left as it is. In one that has some, every bit-vector
 * atom (comparison, equality) that contains a conversion is restated over the signed integer value S(b) of its
 * operands:
 * <pre>
 *   S(constant) = its value          S(int_to_bv t) = wrap(t)          S(a + b), S(a - b), S(-a), S(a * constant) = wrap(..)
 *   S(ite c a b) = ite c S(a) S(b)   S(sign_extend a) = S(a)           S(extract[w-1:0] a) = wrap(S(a))
 *   S(anything else) = the signed integer value of that term           -- the conversion stays, on the term itself
 *   wrap(x) = x when the interval of x lies within the 32 (64) bit range, else ((x + 2^(w-1)) mod 2^w) - 2^(w-1)
 * </pre>
 * This keeps Java's overflow behaviour: nothing is assumed not to overflow, it is either shown by the intervals or
 * written out with {@code mod}. The intervals come from the length bounds of the strings: the bound the executor
 * declares for a string parameter, and for every other string the fact that a Java string is no longer than
 * 2^31 - 1, which is added to the formula as a constraint (without it a solver may answer with a string whose
 * length does not fit an int).
 *
 * <p>Beyond that ({@link Mode#CONNECTED}, {@link Mode#ALL}) a bit-vector variable that only occurs where S can reach
 * it becomes an integer variable with the range of its width, so that no conversion is left on it either; the atoms
 * that mention it are restated as well. {@link #forModel} gives the variable's value back as a bit-vector.
 */
final class BridgeLift {

    enum Mode {
        /** Atoms with a conversion are restated; variables stay bit-vectors (the conversion is left on the variable). */
        ATOMS,
        /** As ATOMS, and the variables that share an atom with a conversion, directly or through other variables, become integers. */
        CONNECTED,
        /** As ATOMS, and every variable that can become an integer does, in every formula that has a conversion. */
        ALL
    }

    private static final BigInteger JAVA_MAX_LENGTH = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MAX_CHAR = BigInteger.valueOf(0x2FFFF);

    /** An integer term with the interval its value lies in; a null end is unbounded. */
    private record Iv(ArithExpr<IntSort> e, BigInteger lo, BigInteger hi) {}

    /** A lifted assertion with what it depends on: the length bounds used, the variables taken as integers, those left as bit-vectors. */
    private record Lifted(BoolExpr formula, Map<Expr<?>, BigInteger> relied, Set<Expr<?>> asInt, Set<Expr<?>> asBv) {}

    private final Context ctx;
    private final Mode mode;
    private final Map<Expr<?>, Boolean> bridge = new HashMap<>();
    private final Map<Expr<?>, Set<Expr<?>>> varsMemo = new HashMap<>();
    private final Map<Expr<?>, Boolean> liftableMemo = new HashMap<>();
    private final Map<Expr<?>, Lifted> lifted = new HashMap<>();
    private final Map<Expr<?>, Iv> signedMemo = new HashMap<>(), integerMemo = new HashMap<>();
    private final Map<Expr<?>, Expr<?>> keepMemo = new HashMap<>();
    private final Map<Expr<?>, IntExpr> intOf = new HashMap<>();
    /** Declared length bounds, read off assertions of the form len(s) <= n. */
    private final Map<Expr<?>, BigInteger> declared = new HashMap<>();

    /** The variables that are integers in the formula produced by the latest apply(). */
    private Set<Expr<?>> intVars = Set.of();
    // what the assertion being lifted depends on
    private Map<Expr<?>, BigInteger> relying;
    private Set<Expr<?>> usedAsInt, usedAsBv;

    private int assertionsLifted, checksWithBridge, variablesAsIntegers;

    BridgeLift(Context ctx) { this(ctx, Mode.CONNECTED); }

    BridgeLift(Context ctx, Mode mode) { this.ctx = ctx; this.mode = mode; }

    /** Forgets everything; the executor calls it when it turns to another method. */
    void reset() {
        bridge.clear(); varsMemo.clear(); liftableMemo.clear(); lifted.clear(); signedMemo.clear(); integerMemo.clear(); keepMemo.clear();
        intOf.clear(); declared.clear(); intVars = Set.of();
    }

    int assertionsLifted() { return assertionsLifted; }
    int checksWithBridge() { return checksWithBridge; }
    int variablesAsIntegers() { return variablesAsIntegers; }

    /** The path condition as it goes to the solver. The list itself is returned when nothing in it carries a conversion. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<BoolExpr> apply(List<BoolExpr> pc) {
        boolean any = false;
        for (BoolExpr c : pc) {
            learnBound(c);
            if (hasBridge(c)) any = true;
        }
        intVars = Set.of();
        if (!any) return pc;
        checksWithBridge++;
        if (mode != Mode.ATOMS) intVars = integerVariables(pc);
        List<BoolExpr> out = new ArrayList<>(pc.size() + 8);
        Map<Expr<?>, BigInteger> bounds = new LinkedHashMap<>();
        Set<Expr<?>> ranged = new LinkedHashSet<>();
        for (BoolExpr c : pc) {
            if (!needs(c)) { out.add(c); continue; }
            Lifted l = lifted.get(c);
            if (l == null || !stillValid(l)) {
                relying = new LinkedHashMap<>();
                usedAsInt = new LinkedHashSet<>();
                usedAsBv = new LinkedHashSet<>();
                signedMemo.clear(); integerMemo.clear(); keepMemo.clear();   // intervals and variables depend on what is in force now
                BoolExpr f = (BoolExpr) bool(c);
                l = new Lifted(f, relying, usedAsInt, usedAsBv);
                lifted.put(c, l);
                assertionsLifted++;
            }
            out.add(l.formula());
            bounds.putAll(l.relied());
            ranged.addAll(l.asInt());
        }
        // what the intervals were computed from, stated in the formula itself
        for (var e : bounds.entrySet()) out.add(ctx.mkLe(ctx.mkLength((Expr) e.getKey()), ctx.mkInt(e.getValue().toString())));
        for (Expr<?> v : ranged) {
            BigInteger half = BigInteger.ONE.shiftLeft(width(v) - 1);
            out.add(ctx.mkGe(intOf.get(v), ctx.mkInt(half.negate().toString())));
            out.add(ctx.mkLe(intOf.get(v), ctx.mkInt(half.subtract(BigInteger.ONE).toString())));
        }
        variablesAsIntegers += ranged.size();
        return out;
    }

    /**
     * e with every variable that the latest apply() turned into an integer replaced by the bit-vector of that
     * integer: what has to be evaluated in a model of the lifted formula to get the value of e.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <R extends com.microsoft.z3.Sort> Expr<R> forModel(Expr<R> e) {
        if (e == null || intVars.isEmpty()) return e;
        List<Expr<?>> from = new ArrayList<>(), to = new ArrayList<>();
        for (Expr<?> v : vars(e)) {
            if (!intVars.contains(v) || !intOf.containsKey(v)) continue;
            from.add(v);
            to.add(ctx.mkInt2BV(width(v), intOf.get(v)));
        }
        if (from.isEmpty()) return e;
        return (Expr<R>) e.substitute(from.toArray(new Expr[0]), to.toArray(new Expr[0]));
    }

    /** After a failed apply(): the formula goes out as it is, so no variable is an integer in its models. */
    void abandon() { intVars = Set.of(); }

    private boolean stillValid(Lifted l) {
        for (var e : l.relied().entrySet()) if (!e.getValue().equals(boundOfSymbol(e.getKey()))) return false;
        for (Expr<?> v : l.asInt()) if (!intVars.contains(v)) return false;
        for (Expr<?> v : l.asBv()) if (intVars.contains(v)) return false;
        return true;
    }

    /** len(s) <= n at the top of the path condition is how the executor bounds a string parameter. */
    private void learnBound(BoolExpr c) {
        if (kind(c) != Z3_decl_kind.Z3_OP_LE || c.getNumArgs() != 2) return;
        Expr<?> l = c.getArgs()[0], r = c.getArgs()[1];
        if (kind(l) != Z3_decl_kind.Z3_OP_SEQ_LENGTH || !(r instanceof IntNum n)) return;
        Expr<?> s = l.getArgs()[0];
        if (!s.isConst()) return;
        BigInteger b = n.getBigInteger();
        BigInteger old = declared.get(s);
        if (old == null || b.compareTo(old) < 0) declared.put(s, b);
    }

    private BigInteger boundOfSymbol(Expr<?> s) {
        BigInteger d = declared.get(s);
        return d == null || d.compareTo(JAVA_MAX_LENGTH) > 0 ? JAVA_MAX_LENGTH : d;
    }

    // ---- shape of terms ---------------------------------------------------------------------------------------

    private static Z3_decl_kind kind(Expr<?> e) {
        return e.isApp() ? e.getFuncDecl().getDeclKind() : null;
    }

    private static int width(Expr<?> b) { return ((BitVecSort) b.getSort()).getSize(); }

    private static boolean isBridge(Z3_decl_kind k) {
        return k == Z3_decl_kind.Z3_OP_INT2BV || k == Z3_decl_kind.Z3_OP_BV2INT || k == Z3_decl_kind.Z3_OP_SBV2INT;
    }

    private static boolean isVariable(Expr<?> e) {
        return e.isBV() && e.isConst() && kind(e) == Z3_decl_kind.Z3_OP_UNINTERPRETED;
    }

    private static boolean isBvPredicate(Z3_decl_kind k) {
        if (k == null) return false;
        switch (k) {
            case Z3_OP_SLT: case Z3_OP_SLEQ: case Z3_OP_SGT: case Z3_OP_SGEQ:
            case Z3_OP_ULT: case Z3_OP_ULEQ: case Z3_OP_UGT: case Z3_OP_UGEQ: return true;
            default: return false;
        }
    }

    /** A comparison or an equality between bit-vectors. */
    private static boolean isBvAtom(Expr<?> e) {
        Z3_decl_kind k = kind(e);
        if (isBvPredicate(k)) return true;
        return (k == Z3_decl_kind.Z3_OP_EQ || k == Z3_decl_kind.Z3_OP_DISTINCT) && e.getNumArgs() >= 2 && e.getArgs()[0].isBV();
    }

    boolean hasBridge(Expr<?> e) {
        Boolean known = bridge.get(e);
        if (known != null) return known;
        boolean r = false;
        if (e.isApp()) {
            if (isBridge(kind(e))) r = true;
            else for (Expr<?> a : e.getArgs()) if (hasBridge(a)) { r = true; break; }
        }
        bridge.put(e, r);
        return r;
    }

    /** The bit-vector variables below e. */
    private Set<Expr<?>> vars(Expr<?> e) {
        Set<Expr<?>> known = varsMemo.get(e);
        if (known != null) return known;
        Set<Expr<?>> r;
        if (isVariable(e)) r = Set.of(e);
        else if (!e.isApp() || e.getNumArgs() == 0) r = Set.of();
        else {
            Expr<?>[] a = e.getArgs();
            if (a.length == 1) r = vars(a[0]);
            else {
                r = new LinkedHashSet<>();
                for (Expr<?> x : a) r.addAll(vars(x));
                if (r.isEmpty()) r = Set.of();
            }
        }
        varsMemo.put(e, r);
        return r;
    }

    private boolean mentionsIntVar(Expr<?> e) {
        if (intVars.isEmpty()) return false;
        for (Expr<?> v : vars(e)) if (intVars.contains(v)) return true;
        return false;
    }

    /** True when something below e has to be restated. */
    private boolean needs(Expr<?> e) { return hasBridge(e) || mentionsIntVar(e); }

    // ---- which variables become integers ------------------------------------------------------------------------

    /** True when S(b) needs no conversion other than on variables. */
    private boolean liftable(Expr<?> b) {
        if (b instanceof BitVecNum || isVariable(b)) return true;
        Boolean known = liftableMemo.get(b);
        if (known != null) return known;
        Z3_decl_kind k = kind(b);
        boolean r = false;
        if (k != null) {
            Expr<?>[] a = b.getArgs();
            switch (k) {
                case Z3_OP_INT2BV: r = true; break;
                case Z3_OP_SIGN_EXT: case Z3_OP_ZERO_EXT: r = liftable(a[0]); break;
                case Z3_OP_EXTRACT: r = b.getFuncDecl().getParameters()[1].getInt() == 0 && liftable(a[0]); break;
                case Z3_OP_BADD: case Z3_OP_BSUB: case Z3_OP_BNEG: r = allLiftable(a); break;
                case Z3_OP_BMUL: r = symbolicFactors(a) <= 1 && allLiftable(a); break;
                case Z3_OP_ITE: r = liftable(a[1]) && liftable(a[2]); break;
                default: r = false;
            }
        }
        liftableMemo.put(b, r);
        return r;
    }

    private boolean allLiftable(Expr<?>[] a) {
        for (Expr<?> x : a) if (!liftable(x)) return false;
        return true;
    }

    private static int symbolicFactors(Expr<?>[] a) {
        int n = 0;
        for (Expr<?> x : a) if (!(x instanceof BitVecNum)) n++;
        return n;
    }

    /**
     * The variables of the path condition that become integers: those that occur nowhere S cannot reach them and,
     * unless the mode is ALL, are linked to a conversion through the atoms they occur in.
     */
    private Set<Expr<?>> integerVariables(List<BoolExpr> pc) {
        Set<Expr<?>> blocked = new HashSet<>();
        Set<Expr<?>> seen = new HashSet<>();
        List<Set<Expr<?>>> groups = new ArrayList<>();   // variables of one atom each
        Set<Expr<?>> seeds = new LinkedHashSet<>();
        for (BoolExpr c : pc) scan(c, blocked, seen, groups, seeds);
        Set<Expr<?>> candidates = new LinkedHashSet<>();
        if (mode == Mode.ALL) {
            for (Set<Expr<?>> g : groups) candidates.addAll(g);
        } else {
            // closure of the variables that share an atom with a conversion
            candidates.addAll(seeds);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Set<Expr<?>> g : groups) {
                    boolean touches = false;
                    for (Expr<?> v : g) if (candidates.contains(v)) { touches = true; break; }
                    if (touches && candidates.addAll(g)) grew = true;
                }
            }
        }
        candidates.removeAll(blocked);
        for (Expr<?> v : candidates) intOf.computeIfAbsent(v, x -> ctx.mkIntConst(x.toString() + "!i"));
        return candidates;
    }

    /** Walks a term that is not inside a liftable position: a variable met here cannot become an integer. */
    private void scan(Expr<?> t, Set<Expr<?>> blocked, Set<Expr<?>> seen, List<Set<Expr<?>>> groups, Set<Expr<?>> seeds) {
        if (isVariable(t)) { blocked.add(t); return; }
        if (!t.isApp() || t.getNumArgs() == 0 || vars(t).isEmpty() && !hasBridge(t)) return;
        if (!seen.add(t)) return;
        Z3_decl_kind k = kind(t);
        Expr<?>[] a = t.getArgs();
        if (isBvAtom(t) && allLiftable(a)) { group(t, a, blocked, seen, groups, seeds); return; }
        if (k == Z3_decl_kind.Z3_OP_ITE && t.isInt()) {
            Expr<?> b = signPattern(t);
            if (b != null && liftable(b)) { group(t, new Expr<?>[]{b}, blocked, seen, groups, seeds); return; }
        }
        if ((k == Z3_decl_kind.Z3_OP_BV2INT || k == Z3_decl_kind.Z3_OP_SBV2INT) && liftable(a[0])) { group(t, a, blocked, seen, groups, seeds); return; }
        for (Expr<?> x : a) scan(x, blocked, seen, groups, seeds);
    }

    private void group(Expr<?> atom, Expr<?>[] operands, Set<Expr<?>> blocked, Set<Expr<?>> seen, List<Set<Expr<?>>> groups, Set<Expr<?>> seeds) {
        Set<Expr<?>> g = new LinkedHashSet<>();
        for (Expr<?> x : operands) inner(x, g, blocked, seen, groups, seeds);
        if (g.isEmpty()) return;
        groups.add(g);
        if (hasBridge(atom)) seeds.addAll(g);
    }

    /** b is liftable: its variables are reachable; conditions and integer arguments below it are scanned as usual. */
    private void inner(Expr<?> b, Set<Expr<?>> g, Set<Expr<?>> blocked, Set<Expr<?>> seen, List<Set<Expr<?>>> groups, Set<Expr<?>> seeds) {
        if (b instanceof BitVecNum) return;
        if (isVariable(b)) { g.add(b); return; }
        Z3_decl_kind k = kind(b);
        Expr<?>[] a = b.getArgs();
        if (k == Z3_decl_kind.Z3_OP_INT2BV) { scan(a[0], blocked, seen, groups, seeds); return; }
        if (k == Z3_decl_kind.Z3_OP_ITE) {
            scan(a[0], blocked, seen, groups, seeds);
            inner(a[1], g, blocked, seen, groups, seeds);
            inner(a[2], g, blocked, seen, groups, seeds);
            return;
        }
        for (Expr<?> x : a) inner(x, g, blocked, seen, groups, seeds);
    }

    // ---- the rewrite --------------------------------------------------------------------------------------------

    /** Keeps the sort of e and lifts what can be lifted below it. */
    private Expr<?> any(Expr<?> e) {
        if (!needs(e)) { usedAsBv.addAll(vars(e)); return e; }
        if (e.isBool()) return bool(e);
        if (e.isInt()) return integer(e).e();
        return keep(e);
    }

    private Expr<?> keep(Expr<?> e) {
        if (isVariable(e)) { usedAsBv.add(e); return e; }
        if (!e.isApp() || e.getNumArgs() == 0 || !needs(e)) { usedAsBv.addAll(vars(e)); return e; }
        Expr<?> done = keepMemo.get(e);
        if (done != null) return done;
        Expr<?>[] args = e.getArgs();
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Expr<?> a = any(args[i]);
            if (a != args[i]) { args[i] = a; changed = true; }
        }
        Expr<?> r = changed ? e.update(args) : e;
        keepMemo.put(e, r);
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Expr<?> bool(Expr<?> e) {
        if (!needs(e)) { usedAsBv.addAll(vars(e)); return e; }
        Z3_decl_kind k = kind(e);
        if (k == null) return e;
        Expr<?>[] a = e.getArgs();
        switch (k) {
            case Z3_OP_SLT: return ctx.mkLt(signed(a[0]).e(), signed(a[1]).e());
            case Z3_OP_SLEQ: return ctx.mkLe(signed(a[0]).e(), signed(a[1]).e());
            case Z3_OP_SGT: return ctx.mkGt(signed(a[0]).e(), signed(a[1]).e());
            case Z3_OP_SGEQ: return ctx.mkGe(signed(a[0]).e(), signed(a[1]).e());
            case Z3_OP_ULT: return ctx.mkLt(unsigned(a[0]).e(), unsigned(a[1]).e());
            case Z3_OP_ULEQ: return ctx.mkLe(unsigned(a[0]).e(), unsigned(a[1]).e());
            case Z3_OP_UGT: return ctx.mkGt(unsigned(a[0]).e(), unsigned(a[1]).e());
            case Z3_OP_UGEQ: return ctx.mkGe(unsigned(a[0]).e(), unsigned(a[1]).e());
            case Z3_OP_EQ:
                if (a.length == 2 && a[0].isBV()) return ctx.mkEq(signed(a[0]).e(), signed(a[1]).e());
                return keep(e);
            case Z3_OP_DISTINCT:
                if (a.length >= 2 && a[0].isBV()) {
                    Expr[] xs = new Expr[a.length];
                    for (int i = 0; i < a.length; i++) xs[i] = signed(a[i]).e();
                    return ctx.mkDistinct(xs);
                }
                return keep(e);
            default: return keep(e);
        }
    }

    // ---- integer terms ----------------------------------------------------------------------------------------

    private BigInteger stringBound(Expr<?> s) {
        if (s.isString()) return BigInteger.valueOf(s.getString().length());   // the escaped text is never shorter
        Z3_decl_kind k = kind(s);
        if (s.isConst()) {
            BigInteger b = boundOfSymbol(s);
            relying.put(s, b);
            return b;
        }
        if (k == null) return null;
        switch (k) {
            case Z3_OP_SEQ_EXTRACT: return stringBound(s.getArgs()[0]);
            case Z3_OP_SEQ_UNIT: case Z3_OP_SEQ_AT: return BigInteger.ONE;
            case Z3_OP_SEQ_EMPTY: return BigInteger.ZERO;
            case Z3_OP_ITE: return max(stringBound(s.getArgs()[1]), stringBound(s.getArgs()[2]));
            case Z3_OP_SEQ_CONCAT: {
                BigInteger sum = BigInteger.ZERO;
                for (Expr<?> p : s.getArgs()) sum = add(sum, stringBound(p));
                return sum;
            }
            default: return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Iv integer(Expr<?> t) {
        if (t instanceof IntNum n) { BigInteger v = n.getBigInteger(); return new Iv((ArithExpr<IntSort>) t, v, v); }
        Iv done = integerMemo.get(t);
        if (done != null) return done;
        Iv r = integer0(t);
        integerMemo.put(t, r);
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Iv integer0(Expr<?> t) {
        Z3_decl_kind k = kind(t);
        ArithExpr<IntSort> same = (ArithExpr<IntSort>) t;
        if (k == null) return new Iv(same, null, null);
        Expr<?>[] a = t.getArgs();
        switch (k) {
            case Z3_OP_ITE: {
                Expr<?> b = signPattern(t);
                if (b != null) return signed(b);
                Iv x = integer(a[1]), y = integer(a[2]);
                return new Iv((ArithExpr<IntSort>) ctx.mkITE((BoolExpr) bool(a[0]), x.e(), y.e()), min(x.lo(), y.lo()), max(x.hi(), y.hi()));
            }
            case Z3_OP_BV2INT: return unsigned(a[0]);
            case Z3_OP_SBV2INT: return signed(a[0]);
            case Z3_OP_UMINUS: {
                Iv x = integer(a[0]);
                return new Iv((ArithExpr<IntSort>) ctx.mkUnaryMinus(x.e()), neg(x.hi()), neg(x.lo()));
            }
            case Z3_OP_ADD: case Z3_OP_SUB: {
                Iv x = integer(a[0]);
                ArithExpr[] es = new ArithExpr[a.length];
                es[0] = x.e();
                BigInteger lo = x.lo(), hi = x.hi();
                for (int i = 1; i < a.length; i++) {
                    Iv y = integer(a[i]);
                    es[i] = y.e();
                    if (k == Z3_decl_kind.Z3_OP_ADD) { lo = add(lo, y.lo()); hi = add(hi, y.hi()); }
                    else { lo = add(lo, neg(y.hi())); hi = add(hi, neg(y.lo())); }
                }
                return new Iv((ArithExpr<IntSort>) (k == Z3_decl_kind.Z3_OP_ADD ? ctx.mkAdd(es) : ctx.mkSub(es)), lo, hi);
            }
            case Z3_OP_MUL: {
                Iv x = integer(a[0]);
                ArithExpr[] es = new ArithExpr[a.length];
                es[0] = x.e();
                BigInteger lo = x.lo(), hi = x.hi();
                for (int i = 1; i < a.length; i++) {
                    Iv y = integer(a[i]);
                    es[i] = y.e();
                    BigInteger[] p = product(lo, hi, y.lo(), y.hi());
                    lo = p[0]; hi = p[1];
                }
                return new Iv((ArithExpr<IntSort>) ctx.mkMul(es), lo, hi);
            }
            case Z3_OP_SEQ_LENGTH: return new Iv((ArithExpr<IntSort>) keep(t), BigInteger.ZERO, stringBound(a[0]));
            case Z3_OP_SEQ_INDEX: return new Iv((ArithExpr<IntSort>) keep(t), BigInteger.ONE.negate(), stringBound(a[0]));
            case Z3_OP_CHAR_TO_INT: return new Iv((ArithExpr<IntSort>) keep(t), BigInteger.ZERO, MAX_CHAR);
            case Z3_OP_STR_TO_CODE: return new Iv((ArithExpr<IntSort>) keep(t), BigInteger.ONE.negate(), MAX_CHAR);
            default: return new Iv((ArithExpr<IntSort>) keep(t), null, null);
        }
    }

    /** b when t is (ite (bvslt b 0) (- (ubv_to_int b) 2^w) (ubv_to_int b)): how a signed conversion is spelled out. */
    private Expr<?> signPattern(Expr<?> t) {
        Expr<?>[] a = t.getArgs();
        if (a.length != 3 || kind(a[0]) != Z3_decl_kind.Z3_OP_SLT) return null;
        Expr<?> b = a[0].getArgs()[0], zero = a[0].getArgs()[1];
        if (!(zero instanceof BitVecNum z) || z.getBigInteger().signum() != 0) return null;
        if (kind(a[2]) != Z3_decl_kind.Z3_OP_BV2INT || !a[2].getArgs()[0].equals(b)) return null;
        if (kind(a[1]) != Z3_decl_kind.Z3_OP_SUB || a[1].getNumArgs() != 2) return null;
        Expr<?> u = a[1].getArgs()[0], m = a[1].getArgs()[1];
        if (kind(u) != Z3_decl_kind.Z3_OP_BV2INT || !u.getArgs()[0].equals(b)) return null;
        if (!(m instanceof IntNum n) || !n.getBigInteger().equals(BigInteger.ONE.shiftLeft(width(b)))) return null;
        return b;
    }

    // ---- bit-vector terms as integers -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Iv wrap(Iv x, int w) {
        BigInteger half = BigInteger.ONE.shiftLeft(w - 1);
        if (x.lo() != null && x.hi() != null && x.lo().compareTo(half.negate()) >= 0 && x.hi().compareTo(half) < 0) return x;
        IntExpr h = ctx.mkInt(half.toString()), full = ctx.mkInt(BigInteger.ONE.shiftLeft(w).toString());
        ArithExpr<IntSort> e = ctx.mkSub(ctx.mkMod(ctx.mkAdd(x.e(), h), full), h);
        return new Iv(e, half.negate(), half.subtract(BigInteger.ONE));
    }

    @SuppressWarnings("unchecked")
    private Iv unsigned(Expr<?> b) {
        Iv x = signed(b);
        if (x.lo() != null && x.lo().signum() >= 0) return x;
        BigInteger full = BigInteger.ONE.shiftLeft(width(b));
        ArithExpr<IntSort> e = (ArithExpr<IntSort>) ctx.mkITE(ctx.mkLt(x.e(), ctx.mkInt(0)), ctx.mkAdd(x.e(), ctx.mkInt(full.toString())), x.e());
        return new Iv(e, BigInteger.ZERO, full.subtract(BigInteger.ONE));
    }

    /** The signed integer value of the bit-vector term b. */
    private Iv signed(Expr<?> b) {
        Iv done = signedMemo.get(b);
        if (done != null) return done;
        Iv r = signed0(b);
        signedMemo.put(b, r);
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Iv signed0(Expr<?> b) {
        int w = width(b);
        BigInteger half = BigInteger.ONE.shiftLeft(w - 1);
        if (b instanceof BitVecNum n) {
            BigInteger v = n.getBigInteger();
            if (v.compareTo(half) >= 0) v = v.subtract(BigInteger.ONE.shiftLeft(w));
            return new Iv(ctx.mkInt(v.toString()), v, v);
        }
        if (isVariable(b)) {
            if (intVars.contains(b)) {
                usedAsInt.add(b);
                return new Iv(intOf.get(b), half.negate(), half.subtract(BigInteger.ONE));
            }
            return leaf(b, w);
        }
        Z3_decl_kind k = kind(b);
        if (k == null) return leaf(b, w);
        Expr<?>[] a = b.getArgs();
        switch (k) {
            case Z3_OP_INT2BV: return wrap(integer(a[0]), w);
            case Z3_OP_SIGN_EXT: return signed(a[0]);
            case Z3_OP_ZERO_EXT: return unsigned(a[0]);
            case Z3_OP_EXTRACT: {
                int low = b.getFuncDecl().getParameters()[1].getInt();
                return low == 0 ? wrap(signed(a[0]), w) : leaf(b, w);
            }
            case Z3_OP_BADD: case Z3_OP_BSUB: {
                Iv x = signed(a[0]);
                ArithExpr[] es = new ArithExpr[a.length];
                es[0] = x.e();
                BigInteger lo = x.lo(), hi = x.hi();
                for (int i = 1; i < a.length; i++) {
                    Iv y = signed(a[i]);
                    es[i] = y.e();
                    if (k == Z3_decl_kind.Z3_OP_BADD) { lo = add(lo, y.lo()); hi = add(hi, y.hi()); }
                    else { lo = add(lo, neg(y.hi())); hi = add(hi, neg(y.lo())); }
                }
                return wrap(new Iv((ArithExpr<IntSort>) (k == Z3_decl_kind.Z3_OP_BADD ? ctx.mkAdd(es) : ctx.mkSub(es)), lo, hi), w);
            }
            case Z3_OP_BNEG: {
                Iv x = signed(a[0]);
                return wrap(new Iv((ArithExpr<IntSort>) ctx.mkUnaryMinus(x.e()), neg(x.hi()), neg(x.lo())), w);
            }
            case Z3_OP_BMUL: {
                if (symbolicFactors(a) > 1) return leaf(b, w);          // a product of two unknowns is no easier over the integers
                Iv x = signed(a[0]);
                ArithExpr[] es = new ArithExpr[a.length];
                es[0] = x.e();
                BigInteger lo = x.lo(), hi = x.hi();
                for (int i = 1; i < a.length; i++) {
                    Iv y = signed(a[i]);
                    es[i] = y.e();
                    BigInteger[] p = product(lo, hi, y.lo(), y.hi());
                    lo = p[0]; hi = p[1];
                }
                return wrap(new Iv((ArithExpr<IntSort>) ctx.mkMul(es), lo, hi), w);
            }
            case Z3_OP_ITE: {
                Iv x = signed(a[1]), y = signed(a[2]);
                return new Iv((ArithExpr<IntSort>) ctx.mkITE((BoolExpr) bool(a[0]), x.e(), y.e()), min(x.lo(), y.lo()), max(x.hi(), y.hi()));
            }
            default: return leaf(b, w);
        }
    }

    /** A term the rewrite does not look into (a variable, a division, a bit operation, an array element): converted as a whole. */
    private Iv leaf(Expr<?> b, int w) {
        BigInteger half = BigInteger.ONE.shiftLeft(w - 1);
        return new Iv(ctx.mkBV2Int((BitVecExpr) keep(b), true), half.negate(), half.subtract(BigInteger.ONE));
    }

    // ---- intervals ---------------------------------------------------------------------------------------------

    private static BigInteger add(BigInteger a, BigInteger b) { return a == null || b == null ? null : a.add(b); }
    private static BigInteger neg(BigInteger a) { return a == null ? null : a.negate(); }
    private static BigInteger min(BigInteger a, BigInteger b) { return a == null || b == null ? null : a.min(b); }
    private static BigInteger max(BigInteger a, BigInteger b) { return a == null || b == null ? null : a.max(b); }

    private static BigInteger[] product(BigInteger lo, BigInteger hi, BigInteger lo2, BigInteger hi2) {
        if (lo == null || hi == null || lo2 == null || hi2 == null) return new BigInteger[]{null, null};
        BigInteger[] c = {lo.multiply(lo2), lo.multiply(hi2), hi.multiply(lo2), hi.multiply(hi2)};
        BigInteger mn = c[0], mx = c[0];
        for (BigInteger x : c) { mn = mn.min(x); mx = mx.max(x); }
        return new BigInteger[]{mn, mx};
    }
}
