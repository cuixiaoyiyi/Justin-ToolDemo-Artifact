package cn.ac.ios.justin.symexec;

import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic record of the solver calls of symbolic execution, off unless {@code -Djustin.smtdump=DIR}
 * is given. Every check is one line of {@code DIR/checks.csv} (method, result, time, number of
 * assertions, which theories the formula mixes); a check that took at least
 * {@code -Djustin.smtdump.ms} milliseconds (default 200) or came back unknown is also written as a
 * self-contained SMT-LIB 2 file that reproduces it, with the solver timeout that was in force.
 *
 * <p>The formula text is taken from the solver after the check and before the pop, so producing it
 * does not count into the measured time; it does cost time overall, so runs with the dump enabled
 * explore less within the same budgets.
 */
final class SmtDump {
    private SmtDump() {}

    private static final Path DIR;
    private static final long SLOW_MS;
    private static final AtomicLong SEQ = new AtomicLong();
    private static final Object LOCK = new Object();

    static {
        String dir = System.getProperty("justin.smtdump");
        Path p = null;
        if (dir != null && !dir.isBlank()) {
            try {
                p = Path.of(dir);
                Files.createDirectories(p.resolve("slow"));
                Path csv = p.resolve("checks.csv");
                if (!Files.exists(csv)) Files.writeString(csv, "seq,method,purpose,result,millis,assertions,strings,bv_int_bridge,chars,arrays,reals,file\n", StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                p = null;
            }
        }
        DIR = p;
        SLOW_MS = Long.getLong("justin.smtdump.ms", 200L);
    }

    static boolean enabled() { return DIR != null; }

    /**
     * @param solver  still holding the assertions of the check
     * @param purpose "feasible" for a branch feasibility check, "model" for the final solve of a path
     */
    static void record(Solver solver, String method, String purpose, Status result, long nanos, int timeoutMs) {
        if (DIR == null) return;
        try {
            long ms = nanos / 1_000_000;
            String text = solver.toString();
            boolean strings = text.contains("String") || text.contains("str.") || text.contains("seq.");
            boolean bridge = text.contains("int_to_bv") || text.contains("bv_to_int") || text.contains("int2bv") || text.contains("bv2int") || text.contains("bv2nat");
            boolean chars = text.contains("char.") || text.contains("Unicode") || text.contains("seq.nth");
            boolean arrays = text.contains("(Array ");
            boolean reals = text.contains(" Real)") || text.contains("to_real") || text.contains("(to_int ");
            long seq = SEQ.incrementAndGet();
            String file = "";
            if (ms >= SLOW_MS || result == Status.UNKNOWN) {
                String name = String.format(Locale.ROOT, "%s-%05dms-%06d-%s.smt2", result == Status.UNKNOWN ? "unknown" : result == Status.SATISFIABLE ? "sat" : "unsat",
                        ms, seq, shortName(method));
                file = "slow/" + name;
                StringBuilder sb = new StringBuilder();
                sb.append("; Justin symbolic execution, solver call ").append(seq).append('\n');
                sb.append("; method:   ").append(method).append('\n');
                sb.append("; purpose:  ").append(purpose).append('\n');
                sb.append("; result:   ").append(result).append(" after ").append(ms).append(" ms (timeout ").append(timeoutMs).append(" ms, Z3 ").append(com.microsoft.z3.Version.getFullVersion()).append(")\n");
                sb.append("; theories: ").append(strings ? "strings " : "").append(bridge ? "bv-int-bridge " : "").append(chars ? "chars " : "").append(arrays ? "arrays " : "").append(reals ? "reals" : "").append('\n');
                sb.append("(set-option :timeout ").append(timeoutMs).append(")\n");
                sb.append(text);
                if (!text.endsWith("\n")) sb.append('\n');
                sb.append("(check-sat)\n");
                Files.writeString(DIR.resolve("slow").resolve(name), sb.toString(), StandardCharsets.UTF_8);
            }
            String line = seq + "," + csv(method) + "," + purpose + "," + result + "," + ms + "," + solver.getNumAssertions() + "," + strings + "," + bridge + "," + chars + "," + arrays + "," + reals + "," + file + "\n";
            synchronized (LOCK) {
                Files.writeString(DIR.resolve("checks.csv"), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            // diagnostics must never disturb generation
        }
    }

    private static String shortName(String method) {
        String m = method == null ? "unknown" : method;
        int paren = m.indexOf('(');
        if (paren > 0) m = m.substring(0, paren);
        int dot = m.lastIndexOf('.', Math.max(0, m.indexOf('#')));
        if (dot >= 0) m = m.substring(dot + 1);
        m = m.replaceAll("[^A-Za-z0-9_.-]", "_");
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static String csv(String s) { return s == null ? "" : '"' + s.replace("\"", "'") + '"'; }
}
