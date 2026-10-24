package cn.ac.ios.justin.exec;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.test.Outcome;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes one input tuple against the target inside the sandbox with a timeout and reports an Outcome.
 * A fresh single-thread executor per Invoker; a timed-out thread is abandoned (and stopped as a last
 * resort) so one runaway tuple cannot wedge the run.
 */
public final class Invoker implements AutoCloseable {
    private Sandbox sandbox;
    private Materializer materializer;
    private final long timeoutMillis;
    private ExecutorService executor;
    /** Captured once: a timed-out thread restoring "whatever was current" could otherwise leave the null stream installed. */
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private static final PrintStream NULL_STREAM = new PrintStream(PrintStream.nullOutputStream());
    // System.out/err are process-wide. Instead of swapping them around every invocation (which also
    // silences the tool's own logging while any subject code runs, and races between threads), they
    // are replaced once by streams that drop what invocation worker threads write and pass everything
    // else through to the original streams.
    private static final String WORKER_NAME = "justin-invoke";
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;

    private static final class ThreadFilteringStream extends java.io.OutputStream {
        private final PrintStream target;
        ThreadFilteringStream(PrintStream target) { this.target = target; }
        private static boolean muted() { return Thread.currentThread().getName().startsWith(WORKER_NAME); }
        @Override public void write(int b) { if (!muted()) target.write(b); }
        @Override public void write(byte[] b, int off, int len) { if (!muted()) target.write(b, off, len); }
        @Override public void flush() { target.flush(); }
    }

    static {
        System.setOut(new PrintStream(new ThreadFilteringStream(ORIGINAL_OUT), true));
        System.setErr(new PrintStream(new ThreadFilteringStream(ORIGINAL_ERR), true));
    }

    private static void mute() { }
    private static void unmute() { }

    public Invoker(Sandbox sandbox, long timeoutMillis) {
        this.sandbox = sandbox;
        this.original = sandbox;
        this.materializer = new Materializer(sandbox);
        this.timeoutMillis = timeoutMillis;
        this.executor = newExecutor();
    }

    private final Sandbox original;
    private int sandboxRefreshes;

    /** How often the sandbox was replaced after a stopped or memory-exhausted invocation. */
    public int sandboxRefreshes() { return sandboxRefreshes; }

    /**
     * Replaces the sandbox after an invocation that was killed or hit a VirtualMachineError: a thread
     * stopped inside a static initializer, or an allocation failure in the middle of the subject's
     * bookkeeping, leaves classes in that loader unusable, and every later tuple of the class would
     * fail for reasons that have nothing to do with the tuple.
     */
    private void refreshSandbox() {
        Sandbox fresh;
        try {
            fresh = sandbox.recreate();
        } catch (RuntimeException e) {
            return;
        }
        if (sandbox != original) sandbox.close();
        sandbox = fresh;
        materializer = new Materializer(fresh);
        sandboxRefreshes++;
    }

    private volatile Thread worker;

    private ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, WORKER_NAME);
            t.setDaemon(true);
            worker = t;
            return t;
        });
    }

    /**
     * A timed-out invocation is interrupted first; code that ignores interrupts (tight loops, huge
     * allocations) is stopped outright, otherwise it keeps burning CPU and heap for the rest of the run.
     * Thread.stop is deprecated but functional on JDK 17; on JDKs where it throws, the thread is abandoned.
     */
    @SuppressWarnings({"deprecation", "removal"})
    private void killWorker() {
        Thread t = worker;
        if (t == null || !t.isAlive()) return;
        t.interrupt();
        try { t.join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (t.isAlive()) {
            try { t.stop(); } catch (Throwable ignored) { }
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Probe hits of the most recent execute() call, or empty when the sandbox is not instrumented. */
    public java.util.Map<String, java.util.BitSet> lastCoverage() { return lastCoverage; }

    /** Reinstates coverage captured earlier (after re-executions elsewhere overwrote nothing here, kept for symmetry). */
    public void restoreCoverage(java.util.Map<String, java.util.BitSet> cov) { lastCoverage = cov; }

    private java.util.Map<String, java.util.BitSet> lastCoverage = java.util.Map.of();

    public Outcome execute(InputTuple tuple) {
        MethodTarget m = tuple.target();
        if (sandbox.probe() != null) sandbox.probe().reset();
        Future<Outcome> f = executor.submit(() -> run(tuple));
        Outcome o = await(f, tuple);
        if (sandbox.probe() != null) lastCoverage = sandbox.probe().drain();
        if (o instanceof Outcome.TimedOut || o instanceof Outcome.SetupFailed sf && "resource exhaustion".equals(sf.message())) refreshSandbox();
        return o;
    }

    /**
     * True when the value can be built in the sandbox. A receiver or argument whose factory rejects its random
     * arguments (Shape.fromNP(6, 7.5)) only turns every tuple it is part of into a setup failure, so the engine
     * asks before it combines.
     */
    public boolean materializes(cn.ac.ios.justin.core.value.Value v) {
        Future<Boolean> f = executor.submit(() -> {
            mute();
            try {
                materializer.materialize(v);
                return true;
            } catch (Exception | LinkageError | StackOverflowError | AssertionError e) {
                return false;
            } finally {
                unmute();
            }
        });
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            executor.shutdownNow();
            killWorker();
            unmute();
            executor = newExecutor();
            return false;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutOfMemoryError) refreshSandbox();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Work that looks at objects in the sandbox; runs on the invocation thread, under the invocation timeout. */
    public interface Inspection<T> {
        T run(Sandbox sandbox, Materializer materializer) throws Exception;
    }

    /**
     * Runs the inspection in the sandbox and returns what it returns, or null when it fails or does not finish
     * in time. Building a value and initializing a class run code of the subject, which is why this does not
     * happen on the caller's thread.
     */
    public <T> T inspect(Inspection<T> work) {
        Sandbox sb = sandbox;
        Materializer mat = materializer;
        Future<T> f = executor.submit(() -> {
            try {
                return work.run(sb, mat);
            } catch (Exception | LinkageError | StackOverflowError | AssertionError e) {
                return null;
            }
        });
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            executor.shutdownNow();
            killWorker();
            executor = newExecutor();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutOfMemoryError) refreshSandbox();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Outcome await(Future<Outcome> f, InputTuple tuple) {
        try {
            return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            executor.shutdownNow();
            killWorker();
            unmute();
            executor = newExecutor();
            return new Outcome.TimedOut(timeoutMillis);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            return new Outcome.SetupFailed(c.getClass().getName(), c.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.SetupFailed("java.lang.InterruptedException", "interrupted");
        }
    }

    private Outcome run(InputTuple tuple) {
        int before = blockedCalls();
        Outcome o = runUnchecked(tuple);
        if ((o instanceof Outcome.Returned || o instanceof Outcome.Threw) && blockedCalls() != before) {
            // a forbidden call was blocked and the subject swallowed the exception: the outcome belongs to the sandbox
            return new Outcome.Blocked("swallowed: " + lastBlockedCall());
        }
        return o;
    }

    /** The counter lives in the sandbox loader's own copy of SandboxSupport. */
    private int blockedCalls() {
        try {
            return sandbox.load(SandboxSupport.class.getName()).getField("blockedCount").getInt(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return 0;
        }
    }

    private String lastBlockedCall() {
        try {
            return String.valueOf(sandbox.load(SandboxSupport.class.getName()).getField("lastBlocked").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "?";
        }
    }

    private Outcome runUnchecked(InputTuple tuple) {
        MethodTarget m = tuple.target();
        Object receiver;
        Object[] args;
        Class<?> cls;
        Class<?>[] ptypes;
        try {
            cls = sandbox.load(m.declaringClass());
            ptypes = materializer.paramTypes(m.descriptor());
            receiver = tuple.receiver() == null ? null : materializer.materialize(tuple.receiver());
            args = new Object[tuple.args().size()];
            for (int i = 0; i < args.length; i++) args[i] = materializer.materialize(tuple.args().get(i));
        } catch (Materializer.SetupException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            return new Outcome.SetupFailed(c.getClass().getName(), c.getMessage());
        } catch (ClassNotFoundException | LinkageError e) {
            return new Outcome.SetupFailed(e.getClass().getName(), e.getMessage());
        }
        mute();
        try {
            Object result;
            if (m.isConstructor()) {
                Constructor<?> ctor = cls.getDeclaredConstructor(ptypes);
                ctor.setAccessible(true);
                result = ctor.newInstance(args);
                return new Outcome.Returned(Observer.observe(result, m.returnType()));
            }
            Method method = cls.getDeclaredMethod(m.name(), ptypes);
            method.setAccessible(true);
            result = method.invoke(receiver, args);
            if (m.returnsVoid()) return new Outcome.Returned(null);
            return new Outcome.Returned(Observer.observe(result, m.returnType()));
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            if (t instanceof VirtualMachineError) {
                // OutOfMemoryError / StackOverflowError depend on the JVM, never on the contract: not a test
                return new Outcome.SetupFailed(t.getClass().getName(), "resource exhaustion");
            }
            if (t instanceof SecurityException se && se.getMessage() != null && se.getMessage().startsWith(SandboxSupport.PREFIX)) {
                return new Outcome.Blocked(se.getMessage().substring(SandboxSupport.PREFIX.length()));
            }
            String tn = t.getClass().getName();
            if (tn.equals("java.lang.reflect.InaccessibleObjectException") || tn.equals("java.lang.IllegalAccessException")
                    || tn.equals("java.lang.IllegalAccessError") || tn.equals("java.lang.SecurityException")) {
                // depends on module access flags of the running JVM, not on the subject's contract
                return new Outcome.SetupFailed(tn, "environment-dependent access failure");
            }
            boolean direct = t.getStackTrace().length > 0 && t.getStackTrace()[0].getClassName().equals(m.declaringClass());
            return new Outcome.Threw(t.getClass().getName(), t.getMessage(), direct);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return new Outcome.SetupFailed(e.getClass().getName(), e.getMessage());
        } finally {
            unmute();
        }
    }

    /** Re-runs the tuple; returns true when every repetition reproduces the first outcome. Preserves lastCoverage of the first run. */
    public boolean isStable(InputTuple tuple, Outcome first, int repetitions) {
        java.util.Map<String, java.util.BitSet> keep = lastCoverage;
        try {
            for (int i = 0; i < repetitions; i++) {
                if (!first.equals(execute(tuple))) return false;
            }
            return true;
        } finally {
            lastCoverage = keep;
        }
    }

    public Materializer materializer() { return materializer; }

    @Override public void close() {
        executor.shutdownNow();
        if (sandbox != original) sandbox.close();
    }

    static boolean isNullValue(Value v) { return v instanceof Value.NullValue; }
}
