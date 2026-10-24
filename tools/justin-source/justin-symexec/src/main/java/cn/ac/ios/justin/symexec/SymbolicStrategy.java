package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.analysis.SootUpLoader;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.InputTuple;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.strategies.GenerationContext;
import cn.ac.ios.justin.strategies.ValueGenerator;
import cn.ac.ios.justin.symexec.strings.RegexChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JInvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.Type;
import sootup.java.core.JavaSootMethod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S5 as a tuple producer: symbolic execution of the target's body yields one input tuple per
 * satisfiable path. Parameters without a symbolic model (objects, collections) are filled by the
 * regular value generator so the tuple is complete.
 *
 * <p>A {@link Session} keeps the executor's work list between calls so the engine can hand out time
 * in rounds (plan section 7.3): every method gets a start budget first, and what is left of the class
 * budget goes to the methods that still make progress.
 */
public final class SymbolicStrategy {
    private static final Logger log = LoggerFactory.getLogger(SymbolicStrategy.class);
    public static final String NAME = "symbolic";

    private final SootUpLoader loader;
    private final SymConfig cfg;
    private final Map<String, Set<String>> calleeCache = new ConcurrentHashMap<>();
    /** Direct supertypes by class, shared by the hierarchies of all methods. */
    private final Map<String, List<String>> parents = new ConcurrentHashMap<>();
    /** Time one sequence of calls gets; it is explored once and closed. */
    private static final long SEQUENCE_SLICE_MS = 1500;
    private static final int MAX_WRITERS = 6, MAX_SEQUENCES = 24;

    public SymbolicStrategy(SootUpLoader loader, SymConfig cfg) {
        this.loader = loader;
        this.cfg = cfg;
    }

    public record Outcome(List<InputTuple> tuples, SymResult.Stats stats, int pathsWithoutModel) {}

    /** One-shot use: open, spend the per-method budget, close. */
    public Outcome generate(GenerationContext ctx, Set<Integer> targetLines, Value receiver) {
        try (Session s = open(ctx, targetLines, receiver)) {
            if (s == null) return new Outcome(List.of(), null, 0);
            List<InputTuple> tuples = s.resume(cfg.methodTimeBudgetMs());
            return new Outcome(tuples, s.stats(), s.incomplete());
        }
    }

    /**
     * Prepares symbolic execution of the target; returns null when the method has no body or no receiver
     * can be built for it.
     *
     * @param targetLines lines the search should reach first (uncovered targets); may be empty
     * @param receiver    value to use as receiver for instance methods (from an earlier pool), or null
     */
    public Session open(GenerationContext ctx, Set<Integer> targetLines, Value receiver) {
        return open(ctx, targetLines, receiver == null ? Map.of() : Map.of("", List.of(receiver)), null);
    }

    /**
     * @param roots  candidate objects by root: "" the receivers (the default one first; a completed path that reads
     *               fields of the receiver takes the first candidate it is feasible for), arg:i the arguments,
     *               ctor:i the objects the default receiver is constructed from
     * @param states gives the concrete state of the candidates (and of static fields), or null to leave the fields free
     */
    public Session open(GenerationContext ctx, Set<Integer> targetLines, Map<String, List<Value>> roots, java.util.function.Function<Map<String, List<Value>>, ReceiverStates> states) {
        MethodTarget m = ctx.target();
        Body body = bodyOf(m);
        if (body == null) return null;
        ValueGenerator gen = new ValueGenerator(ctx);
        roots = new java.util.LinkedHashMap<>(roots);
        List<Value> receivers = roots.getOrDefault("", List.of());
        if (m.isStatic() || m.isConstructor()) {
            receivers = List.of();
        } else if (receivers.isEmpty()) {
            // direct mode or no earlier pool: construct a receiver the same way the other strategies would
            Value receiver = gen.generate(TypeRef.ClassRef.of(m.declaringClass())).orElse(null);
            if (receiver == null) return null;
            receivers = List.of(receiver);
        }
        roots.put("", receivers);
        SymbolicExecutor ex = new SymbolicExecutor(cfg);
        ex.setCalleeResolver(this::calleeBody);
        ValueSource source = new ValueSource() {
            @Override public Value generate(TypeRef type) { return gen.generate(type).orElse(null); }
            @Override public TypeRef concrete(TypeRef declared) { return ctx.concreteType(declared); }
        };
        ex.setValueSource(source);
        TypeOracle oracle = new Hierarchy(loader, ctx.types(), parents);
        ex.setTypeOracle(oracle);
        ReceiverStates concrete = null;
        if (states != null && cfg.objects().receiverCandidates() > 0) {
            for (var e : roots.entrySet()) {
                if (e.getValue().size() > cfg.objects().receiverCandidates()) e.setValue(List.copyOf(e.getValue().subList(0, cfg.objects().receiverCandidates())));
            }
            receivers = roots.get("");
            concrete = states.apply(roots);
            ex.setReceiverStates(concrete);
        } else {
            if (receivers.size() > 1) receivers = List.of(receivers.get(0));
            roots.clear();
            roots.put("", receivers);
        }
        try {
            ex.start(m, body, targetLines == null ? Set.of() : targetLines);
        } catch (RuntimeException | Error e) {
            log.warn("symbolic execution could not start for {}: {}", m.id(), e.toString());
            ex.close();
            return null;
        }
        int branches = 0;
        for (Stmt st : body.getStmts()) if (st.branches()) branches++;
        Session session = new Session(ctx, m, ex, receivers, gen, 1 + Math.min(branches, 20));
        session.roots = roots;
        session.body = body;
        session.targetLines = targetLines == null ? Set.of() : targetLines;
        session.states = concrete;
        session.source = source;
        session.oracle = oracle;
        return session;
    }

    /**
     * The sequences of calls worth trying before m: public methods of the class that write a field m reads, the
     * ones that write a field no candidate receiver provided first. One call each, then the same call twice and
     * pairs of different calls, then three times the same call, as far as the configured length allows.
     */
    private List<List<SymbolicExecutor.Prefix>> sequencesFor(GenerationContext ctx, MethodTarget m, Body body, Set<String> unmet, Map<String, List<Value>> roots) {
        int length = cfg.objects().sequenceLength();
        if (length <= 0 || m.isStatic() || m.isConstructor()) return List.of();
        FieldUse use = new FieldUse(this::calleeBody);
        Set<String> reads = use.of(body).reads();
        List<List<SymbolicExecutor.Prefix>> out = new ArrayList<>();
        // the receiver: its own public methods that write what m reads
        Set<String> wanted = new HashSet<>();
        for (String chain : unmet) if (SymbolicExecutor.rootOf(chain).isEmpty()) wanted.add(chain.contains("/") ? chain.substring(0, chain.indexOf('/')) : chain);
        List<SymbolicExecutor.Prefix> writers = reads.isEmpty() ? List.of() : writers(ctx.owner(), "", use, reads, wanted, MAX_WRITERS);
        for (SymbolicExecutor.Prefix w : writers) out.add(List.of(w));
        if (length >= 2) {
            for (SymbolicExecutor.Prefix w : writers) if (!w.method().params().isEmpty()) out.add(List.of(w, w));
            for (int i = 0; i < writers.size() && i < 3; i++) {
                for (int j = 0; j < writers.size() && j < 3; j++) if (i != j) out.add(List.of(writers.get(i), writers.get(j)));
            }
        }
        if (length >= 3) {
            for (int i = 0; i < writers.size() && i < 2; i++) {
                SymbolicExecutor.Prefix c = writers.get(i);
                if (!c.method().params().isEmpty()) out.add(List.of(c, c, c));
            }
        }
        // the receiver built by its constructor with symbolic arguments, alone and followed by a writer
        SymbolicExecutor.Prefix construct = constructor(ctx, roots);
        if (construct != null) {
            out.add(List.of(construct));
            for (int i = 0; i < writers.size() && i < 2; i++) out.add(List.of(construct, writers.get(i)));
        }
        // the arguments of m, and the objects the receiver is constructed from: public methods of their classes that
        // write the fields the paths needed
        out.addAll(rootSequences(ctx, unmet, roots, construct, use, length));
        return out.size() > MAX_SEQUENCES ? out.subList(0, MAX_SEQUENCES) : out;
    }

    /** Sequences on the objects behind the roots arg:i and ctor:i that paths needed another state of. */
    private List<List<SymbolicExecutor.Prefix>> rootSequences(GenerationContext ctx, Set<String> unmet, Map<String, List<Value>> roots, SymbolicExecutor.Prefix construct,
                                                                FieldUse use, int length) {
        List<List<SymbolicExecutor.Prefix>> out = new ArrayList<>();
        Map<String, Set<String>> wantedByRoot = new java.util.LinkedHashMap<>();
        for (String chain : unmet) {
            String root = SymbolicExecutor.rootOf(chain);
            if (root.isEmpty() || root.equals(SymbolicExecutor.STATIC)) continue;
            List<String> segs = SymbolicExecutor.segments(chain);
            if (!segs.isEmpty()) wantedByRoot.computeIfAbsent(root, r -> new java.util.LinkedHashSet<>()).add(segs.get(0));
        }
        for (var e : wantedByRoot.entrySet()) {
            String root = e.getKey();
            boolean ofCtor = root.startsWith(SymbolicExecutor.CTOR);
            if (ofCtor && construct == null) continue;
            List<Value> candidates = roots.get(root);
            String cls = null;
            if (candidates != null && !candidates.isEmpty() && candidates.get(0) instanceof Value.ObjectValue o) cls = o.type().erasureName();
            if (cls == null && !ofCtor) {
                int i = Integer.parseInt(root.substring(root.indexOf(':') + 1));
                if (i < ctx.target().params().size()) cls = ctx.concreteType(ctx.target().params().get(i).type()).erasureName();
            }
            if (cls == null) continue;
            Optional<ClassTarget> ct = ctx.types().lookup(cls);
            if (ct.isEmpty()) continue;
            List<SymbolicExecutor.Prefix> ws = writers(ct.get(), root, use, e.getValue(), e.getValue(), 3);
            for (SymbolicExecutor.Prefix w : ws) {
                out.add(ofCtor ? List.of(w, construct) : List.of(w));
                if (length >= 2 && !w.method().params().isEmpty()) out.add(ofCtor ? List.of(w, w, construct) : List.of(w, w));
            }
        }
        return out;
    }

    /** Public instance methods of the class that write one of the fields, those that write a wanted field first. */
    private List<SymbolicExecutor.Prefix> writers(ClassTarget cls, String root, FieldUse use, Set<String> reads, Set<String> wanted, int max) {
        record Writer(SymbolicExecutor.Prefix call, int wanted, int read) {}
        List<Writer> writers = new ArrayList<>();
        for (MethodTarget w : cls.methods()) {
            if (!w.isPublic() || w.isStatic() || w.isConstructor() || w.isStaticInitializer() || w.isAbstract() || w.isNative() || w.isSynthetic() || w.isBridge()) continue;
            if (w.params().size() > 4) continue;
            Body wb = bodyOf(w);
            if (wb == null) continue;
            int hitsWanted = 0, hitsRead = 0;
            for (String f : use.of(wb).writes()) {
                if (wanted.contains(f)) hitsWanted++;
                if (reads.contains(f)) hitsRead++;
            }
            if (hitsRead == 0) continue;
            writers.add(new Writer(new SymbolicExecutor.Prefix(w, wb, root), hitsWanted, hitsRead));
        }
        writers.sort((a, b) -> a.wanted() != b.wanted() ? Integer.compare(b.wanted(), a.wanted())
                : a.read() != b.read() ? Integer.compare(b.read(), a.read())
                : a.call().method().params().size() != b.call().method().params().size() ? Integer.compare(a.call().method().params().size(), b.call().method().params().size())
                : a.call().method().id().compareTo(b.call().method().id()));
        List<SymbolicExecutor.Prefix> out = new ArrayList<>();
        for (Writer w : writers) { if (out.size() >= max) break; out.add(w.call()); }
        return out;
    }

    /** The constructor the default receiver is built with, as a stage of the sequence; null when there is none with a body. */
    private SymbolicExecutor.Prefix constructor(GenerationContext ctx, Map<String, List<Value>> roots) {
        List<Value> receivers = roots.get("");
        if (receivers == null || receivers.isEmpty() || !(receivers.get(0) instanceof Value.ObjectValue o) || !(o.recipe() instanceof Value.Recipe.ConstructorCall cc)) return null;
        if (!cc.className().equals(ctx.target().declaringClass())) return null;
        for (MethodTarget c : ctx.owner().methods()) {
            if (!c.isConstructor() || !c.descriptor().equals(cc.descriptor()) || !c.isPublic()) continue;
            Body cb = bodyOf(c);
            if (cb == null || cb.getStmts().size() > 200) return null;
            return new SymbolicExecutor.Prefix(c, cb, SymbolicExecutor.Prefix.CONSTRUCT);
        }
        return null;
    }

    /**
     * Same-class methods the target's body calls directly, as JaCoCo keys (name + descriptor). With
     * inlining enabled their uncovered lines are legitimate targets of the caller's exploration.
     */
    public Set<String> sameClassCallees(MethodTarget m) {
        if (cfg.inlineDepth() <= 0) return Set.of();
        return calleeCache.computeIfAbsent(m.id(), k -> {
            Body body = bodyOf(m);
            if (body == null) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            for (Stmt st : body.getStmts()) {
                AbstractInvokeExpr inv = null;
                if (st instanceof JInvokeStmt is) inv = is.getInvokeExpr().orElse(null);
                else if (st instanceof JAssignStmt as && as.getRightOp() instanceof AbstractInvokeExpr e) inv = e;
                if (inv == null) continue;
                MethodSignature sig = inv.getMethodSignature();
                if (!sig.getDeclClassType().toString().equals(m.declaringClass())) continue;
                if (sig.getName().equals("<init>") || sig.getName().equals("<clinit>")) continue;
                out.add(sig.getName() + descriptorOf(sig));
            }
            return out;
        });
    }

    /**
     * What each target line's branch condition depends on (see {@link TargetAttribution}); lines that
     * belong to same-class callees are attributed in the callee's body.
     */
    public Map<Integer, String> attribute(MethodTarget m, Set<Integer> lines) {
        Map<Integer, String> out = new java.util.LinkedHashMap<>();
        if (lines.isEmpty()) return out;
        Body body = bodyOf(m);
        if (body == null) return out;
        out.putAll(TargetAttribution.attribute(body, m.declaringClass(), lines));
        if (out.size() < lines.size() && cfg.inlineDepth() > 0) {
            for (Stmt st : body.getStmts()) {
                AbstractInvokeExpr inv = null;
                if (st instanceof JInvokeStmt is) inv = is.getInvokeExpr().orElse(null);
                else if (st instanceof JAssignStmt as && as.getRightOp() instanceof AbstractInvokeExpr e) inv = e;
                if (inv == null || !inv.getMethodSignature().getDeclClassType().toString().equals(m.declaringClass())) continue;
                Optional<Body> callee = calleeBody(inv.getMethodSignature());
                if (callee.isEmpty()) continue;
                Set<Integer> rest = new HashSet<>(lines);
                rest.removeAll(out.keySet());
                if (rest.isEmpty()) break;
                TargetAttribution.attribute(callee.get(), m.declaringClass(), rest).forEach(out::putIfAbsent);
            }
        }
        for (int l : lines) out.putIfAbsent(l, TargetAttribution.UNKNOWN);
        return out;
    }

    /**
     * @param patterns per parameter index, the regexes inferred for it (the virtual @Pattern of the parameter)
     * @param samples  per parameter index, strings drawn from those regexes
     */
    public record RegexOutcome(List<InputTuple> tuples, Map<Integer, Set<String>> patterns, Map<Integer, List<String>> samples, int stringPaths) {
        static final RegexOutcome EMPTY = new RegexOutcome(List.of(), Map.of(), Map.of(), 0);
    }

    /**
     * The regex channel (package strings): inputs for the string parameters of the target, aimed at the
     * given lines, or at every branch outcome when the set is empty. Cheap and static, so it runs before
     * the solver; what it reaches is no longer a target of symbolic execution.
     */
    public RegexOutcome regexTuples(GenerationContext ctx, Set<Integer> targetLines, Value receiver) {
        MethodTarget m = ctx.target();
        Body body = bodyOf(m);
        if (body == null) return RegexOutcome.EMPTY;
        try {
            RegexChannel channel = new RegexChannel(body, ctx.config().seed() * 31 + m.id().hashCode(), this::calleeBody);
            if (!channel.applicable()) return RegexOutcome.EMPTY;
            RegexChannel.Result r = channel.generate(targetLines == null ? Set.of() : targetLines, targetLines == null || targetLines.isEmpty());
            if (r.solutions().isEmpty()) return new RegexOutcome(List.of(), r.patterns(), r.samples(), r.stringPaths());
            ValueGenerator gen = new ValueGenerator(ctx);
            if (receiver == null && !m.isStatic() && !m.isConstructor()) {
                receiver = gen.generate(TypeRef.ClassRef.of(m.declaringClass())).orElse(null);
                if (receiver == null) return new RegexOutcome(List.of(), r.patterns(), r.samples(), r.stringPaths());
            }
            List<InputTuple> out = new ArrayList<>();
            Set<List<Value>> seen = new HashSet<>();
            for (RegexChannel.Solution s : r.solutions()) {
                List<Value> args = new ArrayList<>();
                boolean ok = true;
                for (int i = 0; i < m.params().size(); i++) {
                    Value v = channel.valueOf(s, i, m);
                    if (v == null) {
                        Optional<Value> g = gen.generate(m.params().get(i).type());
                        if (g.isEmpty()) { ok = false; break; }
                        v = g.get();
                    }
                    args.add(coerce(v, ctx.concreteType(m.params().get(i).type())));
                }
                if (!ok || !seen.add(args)) continue;
                String src = RegexChannel.NAME + (s.faultVariant() ? ":throws" : ":path") + (s.exact() ? "" : "~");
                out.add(new InputTuple(m, receiver, args, ctx.typeBindings(), src, Set.of(RegexChannel.NAME)));
            }
            return new RegexOutcome(out, r.patterns(), r.samples(), r.stringPaths());
        } catch (RuntimeException | StackOverflowError e) {
            log.debug("regex channel failed for {}: {}", m.id(), e.toString());
            return RegexOutcome.EMPTY;
        }
    }

    private Body bodyOf(MethodTarget m) {
        Optional<JavaSootMethod> om = loader.loadMethod(m);
        if (om.isEmpty() || !om.get().hasBody()) return null;
        try {
            return om.get().getBody();
        } catch (RuntimeException e) {
            log.debug("no body for {}: {}", m.id(), e.toString());
            return null;
        }
    }

    private Optional<Body> calleeBody(MethodSignature sig) {
        try {
            return loader.loadMethod(sig).filter(mm -> mm.hasBody()).map(mm -> (Body) mm.getBody());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** JVM descriptor of a Jimple signature, the key JaCoCo uses for methods. */
    static String descriptorOf(MethodSignature sig) {
        StringBuilder sb = new StringBuilder("(");
        for (Type t : sig.getParameterTypes()) sb.append(descriptorOf(t.toString()));
        return sb.append(')').append(descriptorOf(sig.getType().toString())).toString();
    }

    static String descriptorOf(String jimpleType) {
        StringBuilder dims = new StringBuilder();
        String t = jimpleType;
        while (t.endsWith("[]")) { dims.append('['); t = t.substring(0, t.length() - 2); }
        String base = switch (t) {
            case "int" -> "I"; case "long" -> "J"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C";
            case "short" -> "S"; case "float" -> "F"; case "double" -> "D"; case "void" -> "V";
            default -> "L" + t.replace('.', '/') + ";";
        };
        return dims + base;
    }

    /** Resumable symbolic execution of one method; close releases the solver. */
    public final class Session implements AutoCloseable {
        private final GenerationContext ctx;
        private final MethodTarget m;
        private final SymbolicExecutor ex;
        private final List<Value> receivers;
        private Map<String, List<Value>> roots = Map.of();
        private final ValueGenerator gen;
        private final int complexity;
        private final Set<List<Object>> seen = new HashSet<>();
        private int incomplete;
        private Body body;
        private Set<Integer> targetLines = Set.of();
        private ReceiverStates states;
        private ValueSource source;
        private TypeOracle oracle;
        /** Sequences of calls still to explore, planned once the method on its own has shown which fields are missing. */
        private List<List<SymbolicExecutor.Prefix>> sequences;
        private int nextSequence;
        /** Fields no candidate provided, over the method on its own and the sequences; a second round of planning uses them. */
        private final Map<String, Integer> unmet = new java.util.TreeMap<>();
        private boolean replanned;
        /** Counters of the sequences explored so far (their executors are closed). */
        private SymResult.Stats sequenceStats;
        private int sequencesExplored, sequenceTuples;

        Session(GenerationContext ctx, MethodTarget m, SymbolicExecutor ex, List<Value> receivers, ValueGenerator gen, int complexity) {
            this.ctx = ctx; this.m = m; this.ex = ex; this.receivers = receivers; this.gen = gen; this.complexity = complexity;
        }

        /** Number of candidate receivers the paths are fitted to. */
        public int candidates() { return receivers.size(); }
        /** Chains of fields that paths needed and no candidate provided, with the number of paths. */
        public Map<String, Integer> unmetFields() {
            Map<String, Integer> all = ex.unmetFields();
            unmet.forEach((k, v) -> all.merge(k, v, Integer::sum));
            return all;
        }

        public MethodTarget target() { return m; }
        /** 1 + number of branching statements (capped), the weight for the first round's budget split. */
        public int complexity() { return complexity; }
        public boolean finished() { return ex.finished() && !sequencesLeft(); }
        public SymResult.Stats stats() { return sequenceStats == null ? ex.stats() : ex.stats().plus(sequenceStats); }
        /** Sequences of calls explored, and the tuples whose receiver one of them built. */
        public int sequencesExplored() { return sequencesExplored; }
        public int sequenceTuples() { return sequenceTuples; }

        private boolean sequencesLeft() {
            if (cfg.objects().sequenceLength() <= 0 || receivers.isEmpty() || !(receivers.get(0) instanceof Value.ObjectValue)) return false;
            if (sequences == null) return !ex.unmetFields().isEmpty();
            if (nextSequence < sequences.size()) return true;
            // the sequences may have shown fields of other objects (arguments, what the receiver is built from) that no candidate has
            if (!replanned && !ex.finished()) return false;
            if (!replanned) {
                replanned = true;
                Set<String> known = new HashSet<>();
                for (List<SymbolicExecutor.Prefix> q : sequences) known.add(q.toString());
                List<List<SymbolicExecutor.Prefix>> more = new ArrayList<>();
                for (List<SymbolicExecutor.Prefix> q : sequencesFor(ctx, m, body, unmetFields().keySet(), roots)) if (known.add(q.toString())) more.add(q);
                if (!more.isEmpty()) {
                    sequences = new ArrayList<>(sequences);
                    sequences.addAll(more.size() > MAX_SEQUENCES ? more.subList(0, MAX_SEQUENCES) : more);
                }
            }
            return nextSequence < sequences.size();
        }
        /** Paths whose model could not be completed into a tuple (no generator value for some parameter). */
        public int incomplete() { return incomplete; }

        /** Explores for up to budgetMs more and returns the tuples of the paths completed in that time. */
        public List<InputTuple> resume(long budgetMs) {
            long t0 = System.currentTimeMillis();
            List<InputTuple> out = new ArrayList<>();
            if (!ex.finished()) {
                // while sequences wait, the method on its own gets half of the time
                long share = sequences != null && nextSequence < sequences.size() ? budgetMs / 2 : budgetMs;
                try {
                    out.addAll(tuples(ex.resume(share), List.of()));
                } catch (RuntimeException | Error e) {
                    log.warn("symbolic execution failed for {}: {}", m.id(), e.toString());
                    return out;
                }
            }
            while (sequencesLeft()) {
                long left = budgetMs - (System.currentTimeMillis() - t0);
                if (left < 100) break;
                if (sequences == null) {
                    sequences = sequencesFor(ctx, m, body, ex.unmetFields().keySet(), roots);
                    continue;
                }
                List<SymbolicExecutor.Prefix> calls = sequences.get(nextSequence++);
                try (SymbolicExecutor sx = new SymbolicExecutor(cfg)) {
                    sx.setCalleeResolver(SymbolicStrategy.this::calleeBody);
                    sx.setReceiverStates(states);
                    sx.setValueSource(source);
                    sx.setTypeOracle(oracle);
                    sx.start(m, body, targetLines, calls);
                    List<InputTuple> got = tuples(sx.resume(Math.min(left, SEQUENCE_SLICE_MS)), calls);
                    sx.unmetFields().forEach((k, v) -> unmet.merge(k, v, Integer::sum));
                    sequencesExplored++;
                    sequenceTuples += got.size();
                    out.addAll(got);
                    sequenceStats = sequenceStats == null ? sx.stats() : sequenceStats.plus(sx.stats());
                } catch (RuntimeException | Error e) {
                    log.debug("sequence before {} abandoned: {}", m.id(), e.toString());
                }
            }
            long left = budgetMs - (System.currentTimeMillis() - t0);
            if (!ex.finished() && left >= 100) {
                try {
                    out.addAll(tuples(ex.resume(left), List.of()));
                } catch (RuntimeException | Error e) {
                    log.warn("symbolic execution failed for {}: {}", m.id(), e.toString());
                }
            }
            return out;
        }

        /** Completes the arguments of a call: what the model left open comes from the generator. */
        private List<Value> complete(MethodTarget of, List<Value> solved) {
            List<Value> args = new ArrayList<>();
            for (int i = 0; i < of.params().size(); i++) {
                Parameter param = of.params().get(i);
                Value v = i < solved.size() ? solved.get(i) : null;
                if (v == null) {
                    Optional<Value> g = gen.generate(param.type());
                    if (g.isEmpty()) return null;
                    v = g.get();
                }
                args.add(coerce(v, ctx.concreteType(param.type())));
            }
            return args;
        }

        /** An object after the calls of the sequence made on it (those with the root): its recipe, then the calls with their arguments. */
        private Value after(Value object, String root, List<SymbolicExecutor.Prefix> calls, List<List<Value>> solved) {
            if (!(object instanceof Value.ObjectValue o)) return calls.stream().anyMatch(c -> c.root().equals(root)) ? null : object;
            List<Value.Call> made = new ArrayList<>();
            Value.Recipe start = o.recipe();
            if (start instanceof Value.Recipe.Sequence s) { start = s.base(); made.addAll(s.mutators()); }
            for (int i = 0; i < calls.size(); i++) {
                if (!calls.get(i).root().equals(root)) continue;
                MethodTarget c = calls.get(i).method();
                List<Value> args = complete(c, i < solved.size() ? solved.get(i) : List.of());
                if (args == null) return null;
                made.add(new Value.Call(c.declaringClass(), c.name(), c.descriptor(), args));
            }
            return made.isEmpty() ? object : new Value.ObjectValue(o.type(), new Value.Recipe.Sequence(start, made));
        }

        /**
         * The receiver of a path: built by the constructor stage of the sequence when there is one (its arguments
         * from the model, the objects among them from their candidates with their own calls), otherwise the chosen
         * candidate; then the calls made on it.
         */
        private Value receiverOf(SymResult.PathInput p, List<SymbolicExecutor.Prefix> calls) {
            int k = p.chosen().getOrDefault("", 0);
            if (k < 0 || k >= receivers.size()) k = 0;
            Value base = receivers.get(k);
            for (int i = 0; i < calls.size(); i++) {
                if (!calls.get(i).constructs()) continue;
                MethodTarget c = calls.get(i).method();
                if (!(receivers.get(0) instanceof Value.ObjectValue o) || !(o.recipe() instanceof Value.Recipe.ConstructorCall cc)) return null;
                List<Value> solved = i < p.before().size() ? p.before().get(i) : List.of();
                List<Value> args = new ArrayList<>();
                for (int j = 0; j < c.params().size(); j++) {
                    Value v = j < solved.size() ? solved.get(j) : null;
                    if (v == null && j < cc.args().size()) v = cc.args().get(j);
                    String root = SymbolicExecutor.CTOR + j;
                    if (v instanceof Value.ObjectValue) {
                        List<Value> cands = roots.get(root);
                        int ck = p.chosen().getOrDefault(root, 0);
                        if (cands != null && ck >= 0 && ck < cands.size()) v = cands.get(ck);
                        v = after(v, root, calls, p.before());
                    }
                    if (v == null) {
                        Optional<Value> g = gen.generate(c.params().get(j).type());
                        if (g.isEmpty()) return null;
                        v = g.get();
                    }
                    args.add(coerce(v, ctx.concreteType(c.params().get(j).type())));
                }
                base = new Value.ObjectValue(o.type(), new Value.Recipe.ConstructorCall(cc.className(), cc.descriptor(), args));
                break;
            }
            return after(base, "", calls, p.before());
        }

        private List<InputTuple> tuples(List<SymResult.PathInput> paths, List<SymbolicExecutor.Prefix> calls) {
            List<InputTuple> out = new ArrayList<>();
            for (SymResult.PathInput p : paths) {
                List<Value> args = new ArrayList<>();
                boolean ok = true;
                for (int i = 0; i < m.params().size(); i++) {
                    Parameter param = m.params().get(i);
                    Value v = i < p.args().size() ? p.args().get(i) : null;
                    String root = SymbolicExecutor.ARG + i;
                    if (v == null) {
                        // the candidate the path was solved against, or any value
                        List<Value> cands = roots.get(root);
                        Integer ck = p.chosen().get(root);
                        if (cands != null && ck != null && ck >= 0 && ck < cands.size()) v = cands.get(ck);
                    }
                    if (v == null) {
                        Optional<Value> g = gen.generate(param.type());
                        if (g.isEmpty()) { ok = false; break; }
                        v = g.get();
                    }
                    v = after(v, root, calls, p.before());
                    if (v == null) { ok = false; break; }
                    args.add(coerce(v, ctx.concreteType(param.type())));
                }
                if (!ok) { incomplete++; continue; }
                Value receiver = receivers.isEmpty() ? null : receiverOf(p, calls);
                if (receiver == null && !receivers.isEmpty()) { incomplete++; continue; }
                List<Object> key = new ArrayList<>(args);
                key.add(receiver);
                if (!seen.add(key)) continue;
                String src = NAME + (p.thrown() != null ? ":throws-" + simple(p.thrown()) : ":path") + (calls.isEmpty() ? "" : "+seq") + (p.imprecise() ? "~" : "");
                out.add(new InputTuple(m, receiver, args, ctx.typeBindings(), src, Set.of(NAME)));
            }
            return out;
        }

        @Override public void close() { ex.close(); }
    }

    /** Boxed parameters receive boxed values; everything else passes through. */
    private static Value coerce(Value v, TypeRef t) {
        // a null solved for a parameter of type T must carry the bound type, or "(Object) null" will not compile against Foo<String>
        if (v instanceof Value.NullValue) return new Value.NullValue(t);
        // an array solved for E[] must be created as the bound component type, not as the erasure of E
        if (v instanceof Value.ArrayValue a && t instanceof TypeRef.ArrayRef ar && !a.component().erasureName().equals(ar.component().erasureName())) {
            return new Value.ArrayValue(ar.component(), a.elements());
        }
        if (v instanceof Value.PrimitiveValue p && !(t instanceof TypeRef.Primitive)) {
            return new Value.ObjectValue(TypeRef.ClassRef.of(p.kind().boxed), new Value.Recipe.Boxed(p));
        }
        return v;
    }

    private static String simple(String binary) { return binary.substring(binary.lastIndexOf('.') + 1); }
}
