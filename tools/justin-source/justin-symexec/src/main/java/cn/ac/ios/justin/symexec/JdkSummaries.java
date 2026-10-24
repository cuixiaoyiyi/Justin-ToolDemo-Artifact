package cn.ac.ios.justin.symexec;

import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.CharSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.RealExpr;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.SeqExpr;

import java.util.List;

/**
 * Symbolic semantics for the JDK methods that matter most for branch conditions: String and
 * CharSequence queries, Math on integers, Objects.requireNonNull, Character classification.
 * Returns null when the call is not modeled (the executor then produces a fresh symbol).
 */
final class JdkSummaries {
    private JdkSummaries() {}

    static SymValue apply(SymbolicExecutor ex, SymState s, String owner, String name, SymValue base, List<SymValue> args, String ret) {
        Context ctx = ex.ctx();
        if (owner.equals("java.lang.Object") && name.equals("equals") && base != null && base.link != null && args.size() == 1 && args.get(0) != null
                && args.get(0).kind == SymValue.Kind.STR) {
            // an element of a collection compared with a string is a string
            return string(ex, ctx, s, name, ex.asString(s, base), args);
        }
        switch (owner) {
            case "java.lang.String": case "java.lang.CharSequence": return string(ex, ctx, s, name, base, args);
            case "java.lang.Math": case "java.lang.StrictMath": return math(ex, ctx, s, name, args);
            case "java.util.Objects": {
                if (name.equals("requireNonNull") && !args.isEmpty()) {
                    SymValue a = args.get(0);
                    if (a.nullFlag != null) s.pc.add(ctx.mkNot(a.nullFlag));   // the null path is an NPE the caller treats as contract; we keep the happy path
                    return a;
                }
                if ((name.equals("isNull") || name.equals("nonNull")) && !args.isEmpty()) {
                    BoolExpr n = nullOf(ctx, args.get(0));
                    return SymValue.bv32(ctx.mkITE(name.equals("isNull") ? n : ctx.mkNot(n), ex.bv32(1), ex.bv32(0)));
                }
                if (name.equals("equals") && args.size() == 2 && args.get(0).kind == SymValue.Kind.STR && args.get(1).kind == SymValue.Kind.STR) {
                    return bool(ex, ctx, ctx.mkEq(args.get(0).expr, args.get(1).expr));
                }
                return null;
            }
            case "java.lang.Character": return character(ex, ctx, name, args);
            case "java.lang.Double": case "java.lang.Float": return floating(ex, ctx, name, base, args);
            case "java.lang.Integer": case "java.lang.Long": case "java.lang.Short": case "java.lang.Byte": case "java.lang.Boolean": case "java.lang.Character$": {
                // boxing/unboxing are identities in our model
                if ((name.equals("valueOf") || name.equals("intValue") || name.equals("longValue") || name.equals("booleanValue") || name.equals("shortValue") || name.equals("byteValue"))
                        && (base != null && base.isNumeric() || !args.isEmpty() && args.get(0).isNumeric())) {
                    return base != null && base.isNumeric() ? base : args.get(0);
                }
                if (name.equals("compare") && args.size() == 2 && args.get(0).isNumeric() && args.get(1).isNumeric() && args.get(0).kind == args.get(1).kind && args.get(0).kind != SymValue.Kind.REAL) {
                    BitVecExpr a = args.get(0).bv(), b = args.get(1).bv();
                    return SymValue.bv32(ctx.mkITE(ctx.mkBVSLT(a, b), ex.bv32(-1), ctx.mkITE(ctx.mkEq(a, b), ex.bv32(0), ex.bv32(1))));
                }
                return null;
            }
            default: return null;
        }
    }

    private static SymValue string(SymbolicExecutor ex, Context ctx, SymState s, String name, SymValue base, List<SymValue> args) {
        if (base == null || base.kind != SymValue.Kind.STR || base.expr == null) return null;
        SeqExpr<CharSort> str = ex.seq(base);
        switch (name) {
            case "length": return SymValue.bv32(ex.toBv32(ctx.mkLength(str)));
            case "isEmpty": return bool(ex, ctx, ctx.mkEq(ctx.mkLength(str), ctx.mkInt(0)));
            case "isBlank": return bool(ex, ctx, ctx.mkEq(ctx.mkLength(str), ctx.mkInt(0))); // approximation: blank == empty
            case "charAt": {
                if (args.size() != 1 || args.get(0).kind != SymValue.Kind.BV32) return null;
                IntExpr idx = ex.toInt(args.get(0).bv());
                // out-of-range index throws; keep the in-range path (the caller's bounds checks usually precede)
                s.pc.add(ctx.mkGe(idx, ctx.mkInt(0)));
                s.pc.add(ctx.mkLt(idx, ctx.mkLength(str)));
                Expr<CharSort> ch = ctx.mkNth(str, idx);
                return SymValue.bv32(ex.toBv32(ctx.charToInt(ch)));
            }
            case "equals": {
                if (args.size() != 1) return null;
                SymValue o = ex.asString(s, args.get(0));
                if (o.kind == SymValue.Kind.STR && o.expr != null) {
                    BoolExpr eq = ctx.mkEq(str, o.expr);
                    if (o.nullFlag != null) eq = ctx.mkAnd(ctx.mkNot(o.nullFlag), eq);
                    return bool(ex, ctx, eq);
                }
                if (o.isDefinitelyNull()) return SymValue.bv32(ex.bv32(0));
                return null;
            }
            case "equalsIgnoreCase": case "contentEquals": {
                if (args.size() == 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null) {
                    // equality is a sufficient condition; the solver may also find other case variants via the fresh symbol
                    return bool(ex, ctx, ctx.mkEq(str, args.get(0).expr));
                }
                return null;
            }
            case "startsWith": if (args.size() >= 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null && args.size() == 1) return bool(ex, ctx, ctx.mkPrefixOf(ex.seq(args.get(0)), str)); return null;
            case "endsWith": if (args.size() == 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null) return bool(ex, ctx, ctx.mkSuffixOf(ex.seq(args.get(0)), str)); return null;
            case "contains": if (args.size() == 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null) return bool(ex, ctx, ctx.mkContains(str, ex.seq(args.get(0)))); return null;
            case "indexOf": {
                if (args.size() == 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null) return SymValue.bv32(ex.toBv32(ctx.mkIndexOf(str, ex.seq(args.get(0)), ctx.mkInt(0))));
                if (args.size() == 1 && args.get(0).kind == SymValue.Kind.BV32) {
                    SeqExpr<CharSort> unit = ctx.mkUnit(ctx.charFromBv(args.get(0).bv()));
                    return SymValue.bv32(ex.toBv32(ctx.mkIndexOf(str, unit, ctx.mkInt(0))));
                }
                return null;
            }
            case "substring": {
                if (args.size() == 1 && args.get(0).kind == SymValue.Kind.BV32) {
                    IntExpr from = ex.toInt(args.get(0).bv());
                    s.pc.add(ctx.mkGe(from, ctx.mkInt(0)));
                    s.pc.add(ctx.mkLe(from, ctx.mkLength(str)));
                    return SymValue.str(ctx.mkExtract(str, from, ctx.mkSub(ctx.mkLength(str), from)), null);
                }
                if (args.size() == 2 && args.get(0).kind == SymValue.Kind.BV32 && args.get(1).kind == SymValue.Kind.BV32) {
                    IntExpr from = ex.toInt(args.get(0).bv()), to = ex.toInt(args.get(1).bv());
                    s.pc.add(ctx.mkGe(from, ctx.mkInt(0)));
                    s.pc.add(ctx.mkLe(from, to));
                    s.pc.add(ctx.mkLe(to, ctx.mkLength(str)));
                    return SymValue.str(ctx.mkExtract(str, from, ctx.mkSub(to, from)), null);
                }
                return null;
            }
            case "concat": if (args.size() == 1 && args.get(0).kind == SymValue.Kind.STR && args.get(0).expr != null) return SymValue.str(ctx.mkConcat(str, ex.seq(args.get(0))), null); return null;
            case "toString": return base;
            case "trim": case "strip": case "toUpperCase": case "toLowerCase": case "intern": return null; // fresh symbol: no precise model
            case "hashCode": return null;
            default: return null;
        }
    }

    /** Double/Float: our reals carry no NaN or infinity, so the classification tests are false; boxing is an identity. */
    private static SymValue floating(SymbolicExecutor ex, Context ctx, String name, SymValue base, List<SymValue> args) {
        switch (name) {
            case "isNaN": case "isInfinite": {
                SymValue a = base != null && base.kind == SymValue.Kind.REAL ? base : (args.size() == 1 && args.get(0).kind == SymValue.Kind.REAL ? args.get(0) : null);
                return a == null ? null : bool(ex, ctx, ctx.mkFalse());
            }
            case "isFinite": return args.size() == 1 && args.get(0).kind == SymValue.Kind.REAL ? bool(ex, ctx, ctx.mkTrue()) : null;
            case "valueOf": case "doubleValue": case "floatValue":
                if (base != null && base.kind == SymValue.Kind.REAL) return base;
                return args.size() == 1 && args.get(0).kind == SymValue.Kind.REAL ? args.get(0) : null;
            case "compare":
                if (args.size() == 2 && args.get(0).kind == SymValue.Kind.REAL && args.get(1).kind == SymValue.Kind.REAL) {
                    RealExpr a = (RealExpr) args.get(0).expr, b = (RealExpr) args.get(1).expr;
                    return SymValue.bv32(ctx.mkITE(ctx.mkLt(a, b), ex.bv32(-1), ctx.mkITE(ctx.mkEq(a, b), ex.bv32(0), ex.bv32(1))));
                }
                return null;
            default: return null;
        }
    }

    private static SymValue math(SymbolicExecutor ex, Context ctx, SymState s, String name, List<SymValue> args) {
        if (!args.isEmpty() && args.stream().allMatch(a -> a.kind == SymValue.Kind.REAL)) {
            RealExpr a = (RealExpr) args.get(0).expr;
            switch (name) {
                case "abs": return SymValue.real((RealExpr) ctx.mkITE(ctx.mkLt(a, ctx.mkReal(0)), ctx.mkUnaryMinus(a), a));
                case "max": case "min": {
                    if (args.size() != 2) return null;
                    RealExpr b = (RealExpr) args.get(1).expr;
                    return SymValue.real((RealExpr) (name.equals("max") ? ctx.mkITE(ctx.mkGt(a, b), a, b) : ctx.mkITE(ctx.mkLt(a, b), a, b)));
                }
                default: return null;
            }
        }
        if (args.isEmpty() || args.stream().anyMatch(a -> a.kind != SymValue.Kind.BV32 && a.kind != SymValue.Kind.BV64)) return null;
        if (args.stream().map(a -> a.kind).distinct().count() != 1) return null;
        boolean wide = args.get(0).kind == SymValue.Kind.BV64;
        int width = wide ? 64 : 32;
        BitVecExpr a = args.get(0).bv();
        switch (name) {
            case "abs": { BitVecExpr r = (BitVecExpr) ctx.mkITE(ctx.mkBVSLT(a, ctx.mkBV(0, width)), ctx.mkBVNeg(a), a); return wide ? SymValue.bv64(r) : SymValue.bv32(r); }
            case "max": case "min": {
                if (args.size() != 2) return null;
                BitVecExpr b = args.get(1).bv();
                BitVecExpr r = (BitVecExpr) (name.equals("max") ? ctx.mkITE(ctx.mkBVSGT(a, b), a, b) : ctx.mkITE(ctx.mkBVSLT(a, b), a, b));
                return wide ? SymValue.bv64(r) : SymValue.bv32(r);
            }
            default: return null;
        }
    }

    private static SymValue character(SymbolicExecutor ex, Context ctx, String name, List<SymValue> args) {
        if (args.size() != 1 || args.get(0).kind != SymValue.Kind.BV32) return null;
        BitVecExpr c = args.get(0).bv();
        switch (name) {
            case "isDigit": return bool(ex, ctx, ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('0')), ctx.mkBVSLE(c, ex.bv32('9'))));
            case "isLetter": return bool(ex, ctx, ctx.mkOr(ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('a')), ctx.mkBVSLE(c, ex.bv32('z'))), ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('A')), ctx.mkBVSLE(c, ex.bv32('Z')))));
            case "isLetterOrDigit": return bool(ex, ctx, ctx.mkOr(ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('0')), ctx.mkBVSLE(c, ex.bv32('9'))),
                    ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('a')), ctx.mkBVSLE(c, ex.bv32('z'))), ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('A')), ctx.mkBVSLE(c, ex.bv32('Z')))));
            case "isWhitespace": case "isSpaceChar": return bool(ex, ctx, ctx.mkOr(ctx.mkEq(c, ex.bv32(' ')), ctx.mkEq(c, ex.bv32('\t')), ctx.mkEq(c, ex.bv32('\n')), ctx.mkEq(c, ex.bv32('\r'))));
            case "isUpperCase": return bool(ex, ctx, ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('A')), ctx.mkBVSLE(c, ex.bv32('Z'))));
            case "isLowerCase": return bool(ex, ctx, ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('a')), ctx.mkBVSLE(c, ex.bv32('z'))));
            case "toUpperCase": return SymValue.bv32(ctx.mkITE(ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('a')), ctx.mkBVSLE(c, ex.bv32('z'))), ctx.mkBVSub(c, ex.bv32(32)), c));
            case "toLowerCase": return SymValue.bv32(ctx.mkITE(ctx.mkAnd(ctx.mkBVSGE(c, ex.bv32('A')), ctx.mkBVSLE(c, ex.bv32('Z'))), ctx.mkBVAdd(c, ex.bv32(32)), c));
            case "valueOf": case "charValue": return args.get(0);
            default: return null;
        }
    }

    private static SymValue bool(SymbolicExecutor ex, Context ctx, BoolExpr b) {
        return SymValue.bv32(ctx.mkITE(b, ex.bv32(1), ex.bv32(0)));
    }

    private static BoolExpr nullOf(Context ctx, SymValue v) {
        if (v.isDefinitelyNull()) return ctx.mkTrue();
        if (v.nullFlag != null) return v.nullFlag;
        return ctx.mkFalse();
    }
}
