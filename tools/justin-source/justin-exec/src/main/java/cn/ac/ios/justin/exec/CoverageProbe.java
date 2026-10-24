package cn.ac.ios.justin.exec;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;

import java.io.IOException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * In-process JaCoCo: instruments subject classes as the sandbox loads them and lets the caller
 * drain the probe hits after every invocation. Probe bit sets are cheap to keep per tuple and are
 * enough for minimization, attribution and (later) uncovered-target computation.
 */
public final class CoverageProbe {
    private final IRuntime runtime = new LoggerRuntime();
    private final RuntimeData data = new RuntimeData();
    private final Instrumenter instrumenter = new Instrumenter(runtime);
    private boolean started;

    public synchronized void start() {
        if (started) return;
        try {
            runtime.startup(data);
            started = true;
        } catch (Exception e) {
            throw new IllegalStateException("cannot start JaCoCo runtime", e);
        }
    }

    public byte[] instrument(byte[] classBytes, String name) throws IOException {
        return instrumenter.instrument(classBytes, name);
    }

    private final Map<String, Integer> totals = new HashMap<>();

    /** Probe hits since the last drain, keyed by binary class name; resets the counters. */
    public synchronized Map<String, BitSet> drain() {
        Map<String, BitSet> out = new HashMap<>();
        data.collect(ed -> {
            boolean[] probes = ed.getProbes();
            String name = ed.getName().replace('/', '.');
            totals.put(name, probes.length);
            BitSet bs = new BitSet(probes.length);
            for (int i = 0; i < probes.length; i++) if (probes[i]) bs.set(i);
            if (!bs.isEmpty()) out.put(name, bs);
        }, info -> { }, true);
        return out;
    }

    /** Total probe count of every instrumented class that has been loaded so far. */
    public synchronized Map<String, Integer> probeTotals() { return new HashMap<>(totals); }

    public synchronized void reset() { data.reset(); }

    public synchronized void shutdown() {
        if (!started) return;
        runtime.shutdown();
        started = false;
    }

    /** Total probes per class seen so far, for ratio reporting. */
    public static int probeCount(ExecutionData ed) { return ed.getProbes().length; }
}
