package cn.ac.ios.justin.symexec;

import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BitVecNum;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.CharSort;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.Model;
import com.microsoft.z3.SeqExpr;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The native library loads and the theories we rely on (bit-vectors, strings, arrays) work. */
class Z3SmokeTest {

    @Test
    void bitVectorsAndStrings() {
        try (Context ctx = new Context()) {
            Solver s = ctx.mkSolver();
            BitVecExpr x = ctx.mkBVConst("x", 32);
            // x > 90 && x <= 100  (signed)
            s.add(ctx.mkBVSGT(x, ctx.mkBV(90, 32)), ctx.mkBVSLE(x, ctx.mkBV(100, 32)));
            SeqExpr<CharSort> str = (SeqExpr<CharSort>) ctx.mkConst("s", ctx.getStringSort());
            IntExpr len = ctx.mkLength(str);
            s.add(ctx.mkGe(len, ctx.mkInt(3)), ctx.mkLe(len, ctx.mkInt(16)));
            s.add(ctx.mkPrefixOf(ctx.mkString("ab"), str));
            assertEquals(Status.SATISFIABLE, s.check());
            Model m = s.getModel();
            long xv = ((BitVecNum) m.eval(x, true)).getLong();
            assertTrue(xv > 90 && xv <= 100, String.valueOf(xv));
            Expr<?> sv = m.eval(str, true);
            String text = sv.getString();
            assertTrue(text.startsWith("ab") && text.length() >= 3 && text.length() <= 16, text);
            BoolExpr contradiction = ctx.mkBVSGT(x, ctx.mkBV(200, 32));
            s.push();
            s.add(contradiction);
            assertEquals(Status.UNSATISFIABLE, s.check());
            s.pop();
        }
    }
}
