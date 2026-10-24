package cn.ac.ios.justin.symexec;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Replays dumped solver calls (SmtDump files) twice, as dumped and after the bridge lift, and writes one CSV line
 * per formula: answers, times, how many assertions were rewritten. A formula on which the two answers contradict
 * each other (sat against unsat) is a defect of the rewrite.
 *
 * <pre>
 *   java -cp ... cn.ac.ios.justin.symexec.BridgeLiftReplay OUT.csv TIMEOUT_MS THREADS MODE DIR [DIR ...]
 *   java -cp ... cn.ac.ios.justin.symexec.BridgeLiftReplay print MODE FILE
 * </pre>
 * MODE is ATOMS, CONNECTED or ALL (BridgeLift.Mode).
 * <pre>
 * </pre>
 * The lifted formula carries the Java bound on string lengths, so "sat as dumped, unsat lifted" is a contradiction
 * only when the dumped model keeps its strings within 2^31 - 1; such cases are listed with the note "length-bound".
 */
public final class BridgeLiftReplay {
    private BridgeLiftReplay() {}

    public static void main(String[] args) throws Exception {
        if (args[0].equals("print")) {      // print MODE FILE: the lifted assertions of one formula
            try (Context ctx = new Context(Map.of("model", "true"))) {
                List<BoolExpr> pc = Arrays.asList(ctx.parseSMTLIB2String(strip(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)), null, null, null, null));
                BridgeLift lift = new BridgeLift(ctx, BridgeLift.Mode.valueOf(args[1]));
                List<BoolExpr> lifted = lift.apply(pc);
                for (int i = 0; i < lifted.size(); i++) {
                    boolean same = i < pc.size() && lifted.get(i) == pc.get(i);
                    System.out.println((same ? "  same   " : "  LIFTED ") + lifted.get(i).toString().replaceAll("\s+", " "));
                    if (!same && i < pc.size()) System.out.println("    was  " + pc.get(i).toString().replaceAll("\s+", " "));
                }
            }
            return;
        }
        if (args[0].equals("why")) {        // why MODE FILE: the dumped assertions that the lifted formula's model does not make true
            try (Context ctx = new Context(Map.of("model", "true"))) {
                List<BoolExpr> pc = Arrays.asList(ctx.parseSMTLIB2String(strip(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)), null, null, null, null));
                BridgeLift lift = new BridgeLift(ctx, BridgeLift.Mode.valueOf(args[1]));
                List<BoolExpr> lifted = lift.apply(pc);
                Solver[] keep = new Solver[1];
                System.out.println(check(ctx, lifted, 10000, new long[2], 0, keep));
                com.microsoft.z3.Model m = keep[0].getModel();
                System.out.println(m.toString().replaceAll("[ \n]+", " "));
                for (int i = 0; i < pc.size(); i++) {
                    com.microsoft.z3.Expr<?> v = m.eval(lift.forModel(pc.get(i)), true);
                    if (holds(ctx, m, (BoolExpr) lift.forModel(pc.get(i)))) continue;
                    System.out.println("assertion " + i + ": " + pc.get(i).toString().replaceAll("[ \n]+", " "));
                    System.out.println("   lifted:  " + lifted.get(i).toString().replaceAll("[ \n]+", " "));
                    System.out.println("   evaluates to: " + v.toString().replaceAll("[ \n]+", " "));
                    // the same point given to the solver as equalities: does the dumped formula accept it?
                }
            }
            return;
        }
        Path out = Path.of(args[0]);
        int timeout = Integer.parseInt(args[1]), threads = Integer.parseInt(args[2]);
        BridgeLift.Mode mode = BridgeLift.Mode.valueOf(args[3]);
        List<Path> files = new ArrayList<>();
        for (String d : Arrays.copyOfRange(args, 4, args.length)) {
            try (Stream<Path> s = Files.walk(Path.of(d))) {
                s.filter(p -> p.toString().endsWith(".smt2")).sorted().forEach(files::add);
            }
        }
        System.out.println(files.size() + " formulas, mode " + mode + ", timeout " + timeout + " ms, " + threads + " threads");
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (Path f : files) pool.submit(() -> lines.add(one(f, timeout, mode)));
        pool.shutdown();
        pool.awaitTermination(24, TimeUnit.HOURS);
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(null);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("file,assertions,with_bridge,lifted,int_vars,original,original_ms,lifted_answer,lifted_ms,lift_ms,note");
            sorted.forEach(w::println);
        }
        System.out.println("written " + out);
    }

    private static String one(Path f, int timeout, BridgeLift.Mode mode) {
        String name = f.getParent().getParent().getFileName() + "/" + f.getFileName();
        try (Context ctx = new Context(Map.of("model", "true"))) {
            String text = strip(Files.readString(f, StandardCharsets.UTF_8));
            BoolExpr[] as = ctx.parseSMTLIB2String(text, null, null, null, null);
            List<BoolExpr> pc = Arrays.asList(as);
            BridgeLift lift = new BridgeLift(ctx, mode);
            int withBridge = 0;
            for (BoolExpr a : pc) if (lift.hasBridge(a)) withBridge++;
            long t0 = System.nanoTime();
            List<BoolExpr> lifted = lift.apply(pc);
            long liftMs = (System.nanoTime() - t0) / 1_000_000;
            long[] ms = new long[2];
            Status so = check(ctx, pc, timeout, ms, 0, null);
            Solver[] keep = new Solver[1];
            Status sl = check(ctx, lifted, timeout, ms, 1, keep);
            String note = "";
            if (sl == Status.SATISFIABLE) {
                // the model of the lifted formula, put into the assertions as they were dumped
                com.microsoft.z3.Model m = keep[0].getModel();
                int bad = 0;
                for (BoolExpr a : pc) if (!holds(ctx, m, (BoolExpr) lift.forModel(a))) bad++;
                if (bad > 0) note = "MODEL-FAILS-" + bad;
            }
            if (so != Status.UNKNOWN && sl != Status.UNKNOWN && so != sl) note += so == Status.SATISFIABLE ? "length-bound-or-CONTRADICTION" : "CONTRADICTION";
            return String.join(",", name, String.valueOf(pc.size()), String.valueOf(withBridge), String.valueOf(lift.assertionsLifted()), String.valueOf(lift.variablesAsIntegers()),
                    String.valueOf(so), String.valueOf(ms[0]), String.valueOf(sl), String.valueOf(ms[1]), String.valueOf(liftMs), note);
        } catch (Throwable e) {
            return name + ",,,,,,,,,,ERROR " + e.toString().replace(',', ';').replace('\n', ' ');
        }
    }

    /**
     * True when the assertion holds in the model. The model evaluator leaves some closed terms as they are
     * (char.to_int of a character under int_to_bv, for one); what it leaves is decided by the solver.
     */
    private static boolean holds(Context ctx, com.microsoft.z3.Model m, BoolExpr a) {
        com.microsoft.z3.Expr<?> v = m.eval(a, true).simplify();
        if (v.isTrue()) return true;
        if (v.isFalse()) return false;
        Solver s = ctx.mkSolver();
        var p = ctx.mkParams();
        p.add("timeout", 5000);
        s.setParameters(p);
        s.add(ctx.mkNot((BoolExpr) v));
        return s.check() == Status.UNSATISFIABLE;
    }

    private static Status check(Context ctx, List<BoolExpr> pc, int timeout, long[] ms, int slot, Solver[] keep) {
        Solver s = ctx.mkSolver();
        var p = ctx.mkParams();
        p.add("timeout", timeout);
        s.setParameters(p);
        for (BoolExpr c : pc) s.add(c);
        long t0 = System.nanoTime();
        Status st = s.check();
        ms[slot] = (System.nanoTime() - t0) / 1_000_000;
        if (keep != null) keep[0] = s;
        return st;
    }

    /** The dump sets its own timeout and asks for check-sat; only declarations and assertions are wanted here. */
    private static String strip(String text) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (t.startsWith(";") || t.startsWith("(set-option") || t.startsWith("(check-sat") || t.startsWith("(get-model") || t.startsWith("(exit")) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
