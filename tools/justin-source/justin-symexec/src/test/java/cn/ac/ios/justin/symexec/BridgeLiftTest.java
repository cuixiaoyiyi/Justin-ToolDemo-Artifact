package cn.ac.ios.justin.symexec;

import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.CharSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.SeqSort;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rewrite must not change what a path condition says. Proving the equivalence with the solver is as hard as the
 * formulas the rewrite is there to avoid, so the cases check it through models: several models of the lifted formula
 * are put into the original one, and the models of the original one (restricted the same way) into the lifted one.
 */
class BridgeLiftTest {
    private Context ctx;

    @BeforeEach void open() { ctx = new Context(Map.of("model", "true")); }
    @AfterEach void close() { ctx.close(); }

    private Expr<SeqSort<CharSort>> str(String name) { return ctx.mkConst(name, ctx.getStringSort()); }
    private BitVecExpr bv(long v) { return ctx.mkBV(v, 32); }
    /** s.length() as the executor models it. */
    private BitVecExpr length(Expr<SeqSort<CharSort>> s) { return ctx.mkInt2BV(32, ctx.mkLength(s)); }
    private BoolExpr bounded(Expr<SeqSort<CharSort>> s, int n) { return ctx.mkLe(ctx.mkLength(s), ctx.mkInt(n)); }

    private Solver solver(List<BoolExpr> pc) {
        Solver s = ctx.mkSolver();
        var p = ctx.mkParams();
        p.add("timeout", 20_000);
        s.setParameters(p);
        for (BoolExpr c : pc) s.add(c);
        return s;
    }

    private Status check(List<BoolExpr> pc) { return solver(pc).check(); }

    private static boolean bridgeOnStringTerm(List<BoolExpr> pc) {
        for (BoolExpr c : pc) if (c.toString().replaceAll("[ \n\r\t]+", " ").contains("int_to_bv 32) (str.")) return true;
        return false;
    }

    /**
     * In every mode: the lifted formula has up to five different models and each of them satisfies the original
     * assertions; when it has none the original has none either (within the side conditions the lifted one states).
     * strings and variables are what the models are told apart by and what is carried over.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertSameMeaning(List<BoolExpr> pc, List<Expr<SeqSort<CharSort>>> strings, BitVecExpr... variables) {
        for (BridgeLift.Mode mode : BridgeLift.Mode.values()) {
            BridgeLift l = new BridgeLift(ctx, mode);
            List<BoolExpr> lifted = l.apply(pc);
            Solver s = solver(lifted);
            int models = 0;
            for (int round = 0; round < 5; round++) {
                Status st = s.check();
                assertNotEquals(Status.UNKNOWN, st, mode + ": lifted formula not decided");
                if (st == Status.UNSATISFIABLE) break;
                models++;
                Model m = s.getModel();
                // the model's values as equalities on the original symbols
                List<BoolExpr> fixed = new ArrayList<>(), differ = new ArrayList<>();
                for (Expr<SeqSort<CharSort>> x : strings) {
                    Expr<?> v = m.eval(x, true);
                    fixed.add(ctx.mkEq(x, (Expr) v));
                    differ.add(ctx.mkNot(ctx.mkEq(x, (Expr) v)));
                }
                for (BitVecExpr x : variables) {
                    Expr<?> v = m.eval(l.forModel(x), true);
                    assertInstanceOf(BitVecNum.class, v);
                    fixed.add(ctx.mkEq(x, (Expr) v));
                    differ.add(ctx.mkNot(ctx.mkEq(l.forModel(x), (Expr) v)));
                }
                List<BoolExpr> q = new ArrayList<>(pc);
                q.addAll(fixed);
                assertEquals(Status.SATISFIABLE, check(q), mode + ": a model of the lifted formula does not satisfy the original one: " + fixed);
                s.add(ctx.mkOr(differ.toArray(new BoolExpr[0])));
            }
            if (models == 0) {
                // nothing satisfies the lifted formula: then nothing may satisfy the original one with Java-sized strings
                List<BoolExpr> q = new ArrayList<>(pc);
                for (int i = pc.size(); i < lifted.size(); i++) if (lifted.get(i).toString().startsWith("(<= (str.len")) q.add(lifted.get(i));
                assertEquals(Status.UNSATISFIABLE, check(q), mode.toString());
            }
        }
    }

    /** The other direction on one point: the given values satisfy the original formula and the lifted one alike. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertHoldsInBoth(boolean expected, List<BoolExpr> pc, Map<Expr<?>, Expr<?>> values) {
        for (BridgeLift.Mode mode : BridgeLift.Mode.values()) {
            BridgeLift l = new BridgeLift(ctx, mode);
            List<BoolExpr> lifted = l.apply(pc);
            List<BoolExpr> a = new ArrayList<>(pc), b = new ArrayList<>(lifted);
            for (var e : values.entrySet()) {
                a.add(ctx.mkEq((Expr) e.getKey(), (Expr) e.getValue()));
                Expr<?> k = e.getKey().isBV() ? l.forModel((Expr) e.getKey()) : e.getKey();
                b.add(ctx.mkEq((Expr) k, (Expr) e.getValue()));
            }
            assertEquals(expected ? Status.SATISFIABLE : Status.UNSATISFIABLE, check(a), "original, " + values);
            assertEquals(expected ? Status.SATISFIABLE : Status.UNSATISFIABLE, check(b), mode + " lifted, " + values);
        }
    }

    @Test
    void formulaWithoutConversionIsReturnedAsItIs() {
        Expr<SeqSort<CharSort>> s = str("s");
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkContains(s, ctx.mkString("ab")), ctx.mkBVSLT(ctx.mkBVConst("i", 32), bv(7)));
        for (BridgeLift.Mode mode : BridgeLift.Mode.values()) {
            BridgeLift l = new BridgeLift(ctx, mode);
            assertSame(pc, l.apply(pc));
            assertEquals(0, l.assertionsLifted());
        }
    }

    @Test
    void lengthComparisonMovesToTheIntegers() {
        Expr<SeqSort<CharSort>> s = str("s");
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSGT(length(s), bv(5)), ctx.mkNot(ctx.mkEq(length(s), bv(9))));
        List<BoolExpr> lifted = new BridgeLift(ctx).apply(pc);
        assertTrue(bridgeOnStringTerm(pc));
        assertFalse(bridgeOnStringTerm(lifted), lifted.toString());
        assertFalse(lifted.toString().contains("mod"), "a length of at most 32 cannot overflow: " + lifted);
        assertSameMeaning(pc, List.of(s));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("abcdefg")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("abcdefghi")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("abc")));
    }

    @Test
    void indexVariableBecomesAnInteger() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr i = ctx.mkBVConst("i", 32);
        IntExpr idx = ctx.mkBV2Int(i, true);
        BitVecExpr ch = ctx.mkInt2BV(32, ctx.charToInt(ctx.mkNth(s, idx)));
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkGe(idx, ctx.mkInt(0)), ctx.mkLt(idx, ctx.mkLength(s)),
                ctx.mkEq(ch, bv('x')), ctx.mkBVSLT(ctx.mkBVAdd(i, bv(1)), length(s)));
        List<BoolExpr> atoms = new BridgeLift(ctx, BridgeLift.Mode.ATOMS).apply(pc);
        assertFalse(bridgeOnStringTerm(atoms), atoms.toString());
        assertTrue(atoms.toString().contains("ubv_to_int i"), "the conversion stays on the variable: " + atoms);
        List<BoolExpr> connected = new BridgeLift(ctx, BridgeLift.Mode.CONNECTED).apply(pc);
        assertFalse(connected.toString().contains("ubv_to_int") || connected.toString().contains("int_to_bv"), connected.toString());
        assertSameMeaning(pc, List.of(s), i);
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("axb"), i, bv(1)));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("ax"), i, bv(1)));      // i + 1 < length fails
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("axb"), i, bv(-1)));
    }

    @Test
    void overflowIsKeptNotAssumedAway() {
        Expr<SeqSort<CharSort>> s = str("s");
        // s.length() + Integer.MAX_VALUE < 0 holds in Java for every non-empty string
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSLT(ctx.mkBVAdd(length(s), bv(Integer.MAX_VALUE)), bv(0)));
        List<BoolExpr> lifted = new BridgeLift(ctx).apply(pc);
        assertTrue(lifted.toString().contains("mod"), lifted.toString());
        assertSameMeaning(pc, List.of(s));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("a")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("")));
    }

    @Test
    void overflowOfAVariable() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr i = ctx.mkBVConst("i", 32);
        // i + 1 < s.length() with i == Integer.MAX_VALUE: i + 1 wraps to the smallest int, which is below any length
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSLT(ctx.mkBVAdd(i, bv(1)), length(s)));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString(""), i, bv(Integer.MAX_VALUE)));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString(""), i, bv(0)));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("ab"), i, bv(0)));
        assertSameMeaning(pc, List.of(s), i);
    }

    @Test
    void booleanResultOfAStringQueryComparedWithZero() {
        Expr<SeqSort<CharSort>> s = str("s");
        // if (s.length() > 3) as a value: ite(.., 1, 0) != 0
        BitVecExpr flag = (BitVecExpr) ctx.mkITE(ctx.mkBVSGT(length(s), bv(3)), bv(1), bv(0));
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkNot(ctx.mkEq(flag, bv(0))));
        assertFalse(bridgeOnStringTerm(new BridgeLift(ctx).apply(pc)));
        assertSameMeaning(pc, List.of(s));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("abcd")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("abc")));
    }

    @Test
    void stringWithoutDeclaredBoundGetsTheJavaBound() {
        Expr<SeqSort<CharSort>> s = str("u7_s");
        List<BoolExpr> pc = List.of(ctx.mkBVSLT(length(s), bv(0)));
        // without a bound the formula has a model: a length of 2^31 wraps to a negative int; no Java string is that long
        List<BoolExpr> lifted = new BridgeLift(ctx).apply(pc);
        assertTrue(lifted.stream().anyMatch(c -> c.toString().contains("2147483647")), lifted.toString());
        assertEquals(Status.UNSATISFIABLE, check(lifted));
    }

    @Test
    void indexOfAndSubstring() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr at = ctx.mkInt2BV(32, ctx.mkIndexOf(s, ctx.mkString(":"), ctx.mkInt(0)));
        IntExpr from = ctx.mkBV2Int((BitVecExpr) ctx.mkBVAdd(at, bv(1)), true);
        Expr<SeqSort<CharSort>> tail = ctx.mkExtract(s, from, ctx.mkSub(ctx.mkLength(s), from));
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSGE(at, bv(0)), ctx.mkGe(from, ctx.mkInt(0)), ctx.mkLe(from, ctx.mkLength(s)),
                ctx.mkBVSGT(ctx.mkInt2BV(32, ctx.mkLength(tail)), bv(2)));
        List<BoolExpr> lifted = new BridgeLift(ctx).apply(pc);
        assertFalse(bridgeOnStringTerm(lifted), lifted.toString());
        assertSameMeaning(pc, List.of(s));
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("k:abc")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("k:ab")));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("kabcd")));
    }

    @Test
    void variableUsedInABitOperationStaysABitVector() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr i = ctx.mkBVConst("i", 32), j = ctx.mkBVConst("j", 32);
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSLT(i, length(s)), ctx.mkEq(ctx.mkBVAND(i, bv(1)), bv(0)), ctx.mkBVSLT(j, i), ctx.mkBVSGT(j, bv(3)));
        BridgeLift l = new BridgeLift(ctx, BridgeLift.Mode.CONNECTED);
        l.apply(pc);
        assertSame(i, l.forModel(i), "i occurs under bvand");
        assertNotSame(j, l.forModel(j), "j only occurs in comparisons, and is linked to the length through i");
        assertSameMeaning(pc, List.of(s), i, j);
        assertHoldsInBoth(true, pc, Map.of(s, ctx.mkString("abcdefghi"), i, bv(6), j, bv(4)));
        assertHoldsInBoth(false, pc, Map.of(s, ctx.mkString("abcdefghi"), i, bv(7), j, bv(4)));
    }

    @Test
    void variableWithoutALinkToAConversionIsLeftAlone() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr k = ctx.mkBVConst("k", 32);
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkBVSGT(length(s), bv(2)), ctx.mkBVSLT(k, bv(10)));
        BridgeLift connected = new BridgeLift(ctx, BridgeLift.Mode.CONNECTED), all = new BridgeLift(ctx, BridgeLift.Mode.ALL);
        List<BoolExpr> c = connected.apply(pc);
        assertSame(pc.get(2), c.get(2));
        assertSame(k, connected.forModel(k));
        all.apply(pc);
        assertNotSame(k, all.forModel(k));
        assertSameMeaning(pc, List.of(s), k);
    }

    @Test
    void modelGivesTheValueOfAVariableThatBecameAnInteger() {
        Expr<SeqSort<CharSort>> s = str("s");
        BitVecExpr i = ctx.mkBVConst("i", 32);
        List<BoolExpr> pc = List.of(bounded(s, 32), ctx.mkEq(ctx.mkLength(s), ctx.mkInt(7)), ctx.mkEq(ctx.mkBVAdd(i, bv(2)), length(s)));
        BridgeLift l = new BridgeLift(ctx, BridgeLift.Mode.CONNECTED);
        Solver solver = solver(l.apply(pc));
        assertEquals(Status.SATISFIABLE, solver.check());
        assertEquals(5, ((BitVecNum) solver.getModel().eval(l.forModel(i), true)).getInt());
    }

    @Test
    void sameAssertionIsRewrittenOnce() {
        Expr<SeqSort<CharSort>> s = str("s");
        BoolExpr b = bounded(s, 32), c = ctx.mkBVSGT(length(s), bv(5));
        BridgeLift l = new BridgeLift(ctx);
        l.apply(List.of(b, c));
        l.apply(List.of(b, c, ctx.mkBVSLT(length(s), bv(20))));
        assertEquals(2, l.assertionsLifted());
        assertEquals(2, l.checksWithBridge());
    }
}
