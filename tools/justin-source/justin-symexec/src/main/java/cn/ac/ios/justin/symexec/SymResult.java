package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.core.value.Value;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What one symbolic execution produced, plus the counters the report needs. */
public record SymResult(List<PathInput> inputs, Stats stats) {

    /**
     * A satisfiable path: concrete argument values (null entries mean "no symbolic value, use a
     * generator"), the source lines the path touched, the exception it ended with (or null), and
     * whether unmodeled operations made it imprecise. receiver: the candidate receiver (see
     * {@link ReceiverStates}) whose fields make the path feasible, or -1 when the path read no field of the
     * receiver, there were no candidates, or none of them fits. chosen: the candidate per root ("" the receiver,
     * arg:i an argument, ctor:i an argument of the constructor) whose state the path was solved against. before: the arguments of the calls made on the
     * receiver before the method (empty when the method was explored on its own), null entries as in args.
     */
    public record PathInput(List<Value> args, Set<Integer> lines, String thrown, boolean imprecise, List<String> imprecisions, int receiver,
                            List<List<Value>> before, Map<String, Integer> chosen) {
        public PathInput(List<Value> args, Set<Integer> lines, String thrown, boolean imprecise, List<String> imprecisions, int receiver, List<List<Value>> before) {
            this(args, lines, thrown, imprecise, imprecisions, receiver, before, receiver >= 0 ? Map.of("", receiver) : Map.of());
        }
    }

    /**
     * Completed paths that read fields of the receiver: fitted ones found a candidate receiver they are feasible
     * for, unfitted ones are satisfiable only with field values no candidate has. withStatics: paths without
     * fields of the receiver that were solved with the values of the static fields they read.
     */
    public record ReceiverUse(int paths, int fitted, int unfitted, int withStatics) {}

    /**
     * Cumulative counters of one method's session. stopReason: why the last resume ended before the
     * work list was empty ("time", "paths", "no-gain", "solver-timeouts"), or null; finished: the work
     * list is empty or the method was cut for good; targetsReached: target lines some satisfiable path
     * touched; inlinedCalls: same-class calls entered instead of summarized; solver: what the solver was asked.
     */
    /**
     * Solver calls of one method's session. callsWithBridge: calls whose path condition carried a conversion between
     * bit-vectors and integers; assertionsLifted: assertions the bridge lift restated (each one once);
     * variablesAsIntegers: variables taken as integers, summed over the calls.
     */
    public record SolverUse(int calls, long millis, int callsWithBridge, int assertionsLifted, int variablesAsIntegers) {}

    public record Stats(int pathsExplored, int pathsSatisfiable, int pathsUnsat, int solverTimeouts, int loopBoundHits, int stepBoundHits,
                        int constraintBoundHits, int pathBoundHit, long millis, boolean timeBudgetHit, Map<String, Integer> unmodeledCalls,
                        String stopReason, int targetsReached, int inlinedCalls, boolean finished, ReceiverUse receiver, SolverUse solver) {
        /** The counters of two explorations of the same method added up; finished and stopReason are those of this one. */
        public Stats plus(Stats o) {
            Map<String, Integer> calls = new java.util.TreeMap<>(unmodeledCalls);
            o.unmodeledCalls.forEach((k, v) -> calls.merge(k, v, Integer::sum));
            ReceiverUse r = receiver == null ? o.receiver : o.receiver == null ? receiver
                    : new ReceiverUse(receiver.paths + o.receiver.paths, receiver.fitted + o.receiver.fitted, receiver.unfitted + o.receiver.unfitted, receiver.withStatics + o.receiver.withStatics);
            SolverUse s = solver == null ? o.solver : o.solver == null ? solver
                    : new SolverUse(solver.calls + o.solver.calls, solver.millis + o.solver.millis, solver.callsWithBridge + o.solver.callsWithBridge,
                            solver.assertionsLifted + o.solver.assertionsLifted, solver.variablesAsIntegers + o.solver.variablesAsIntegers);
            return new Stats(pathsExplored + o.pathsExplored, pathsSatisfiable + o.pathsSatisfiable, pathsUnsat + o.pathsUnsat, solverTimeouts + o.solverTimeouts,
                    loopBoundHits + o.loopBoundHits, stepBoundHits + o.stepBoundHits, constraintBoundHits + o.constraintBoundHits, pathBoundHit + o.pathBoundHit,
                    millis + o.millis, timeBudgetHit || o.timeBudgetHit, calls, stopReason, targetsReached + o.targetsReached, inlinedCalls + o.inlinedCalls,
                    finished, r, s);
        }

        /** The method was cut by explosion detection rather than by a plain bound. */
        public boolean exploded() { return "no-gain".equals(stopReason) || "solver-timeouts".equals(stopReason); }
    }
}
