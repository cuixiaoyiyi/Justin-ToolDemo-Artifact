package cn.ac.ios.justin.llm;

import cn.ac.ios.justin.core.target.AnnotationInfo;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.test.BodyFacts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Produces copies of method targets whose parameters carry the model's virtual annotations in
 * addition to the real ones. Everything downstream (annotation strategy, relations, exception
 * classification) then treats them like declared contracts, with "llm" as the strategy name.
 * Inference for the methods of one class runs in parallel; model latency dominates otherwise.
 */
public final class AnnotationAugmenter implements AutoCloseable {
    private final AnnotationInferrer inferrer;
    private final List<Path> sourceRoots;
    private final ExecutorService pool;
    private int augmented;

    public AnnotationAugmenter(AnnotationInferrer inferrer, List<Path> sourceRoots) { this(inferrer, sourceRoots, 4); }

    public AnnotationAugmenter(AnnotationInferrer inferrer, List<Path> sourceRoots, int parallelism) {
        this.inferrer = inferrer;
        this.sourceRoots = sourceRoots;
        this.pool = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> { Thread t = new Thread(r, "justin-llm"); t.setDaemon(true); return t; });
    }

    public int augmentedMethods() { return augmented; }
    public AnnotationInferrer inferrer() { return inferrer; }

    /** Infers annotations for all given methods concurrently; returns the augmented copy per method (same object when nothing was inferred). */
    public Map<MethodTarget, MethodTarget> augmentAll(ClassTarget owner, Map<MethodTarget, BodyFacts> methods) {
        Map<MethodTarget, Future<AnnotationInferrer.Inference>> futures = new LinkedHashMap<>();
        for (var e : methods.entrySet()) {
            MethodTarget m = e.getKey();
            BodyFacts facts = e.getValue();
            futures.put(m, pool.submit(() -> inferrer.infer(owner, m, facts, SourceSnippets.withCallees(sourceRoots, owner, m))));
        }
        Map<MethodTarget, MethodTarget> out = new LinkedHashMap<>();
        for (var e : futures.entrySet()) {
            AnnotationInferrer.Inference inf;
            try { inf = e.getValue().get(); } catch (Exception ex) { inf = AnnotationInferrer.Inference.EMPTY; }
            out.put(e.getKey(), apply(e.getKey(), inf));
        }
        return out;
    }

    public MethodTarget augment(ClassTarget owner, MethodTarget m, BodyFacts facts) {
        return apply(m, inferrer.infer(owner, m, facts, SourceSnippets.withCallees(sourceRoots, owner, m)));
    }

    private java.util.function.Function<String, java.util.Optional<cn.ac.ios.justin.core.target.ClassTarget>> classes = n -> java.util.Optional.empty();

    /** Lets inferred @Field groups be checked against the parameter's class: unknown fields are dropped. */
    public AnnotationAugmenter withClassResolver(java.util.function.Function<String, java.util.Optional<cn.ac.ios.justin.core.target.ClassTarget>> resolver) {
        if (resolver != null) this.classes = resolver;
        return this;
    }

    /** Drops @Field groups naming fields the (resolvable) class of the parameter does not have. */
    private List<AnnotationInfo> validateFields(List<AnnotationInfo> anns, cn.ac.ios.justin.core.type.TypeRef type) {
        String owner = type instanceof cn.ac.ios.justin.core.type.TypeRef.ArrayRef a ? a.component().erasureName() : type.erasureName();
        List<AnnotationInfo> out = new ArrayList<>();
        boolean skipping = false;
        String current = owner;
        for (AnnotationInfo a : anns) {
            String simple = a.simpleName();
            if (simple.equals("SubClass") && a.values().get("value") != null) current = a.values().get("value").toString();
            if (simple.equals("Field")) {
                var ct = classes.apply(current);
                String name = String.valueOf(a.values().get("value"));
                skipping = ct.isPresent() && ct.get().fields().stream().noneMatch(f -> f.name().equals(name));
                if (skipping) continue;
            } else if (simple.equals("Element")) {
                skipping = false;
            } else if (skipping) continue;
            out.add(a);
        }
        return out;
    }

    private MethodTarget apply(MethodTarget m, AnnotationInferrer.Inference inf) {
        if (inf.byParam().isEmpty() && inf.returns().isEmpty()) return m;
        List<AnnotationInfo> returns = new ArrayList<>(m.annotations());
        for (AnnotationInfo a : inf.returns()) {
            if (returns.stream().noneMatch(d -> d.simpleName().equals(a.simpleName()))) returns.add(a);
        }
        List<Parameter> params = new ArrayList<>();
        for (Parameter p : m.params()) {
            List<AnnotationInfo> extra = inf.byParam().get(p.index());
            if (extra != null) extra = validateFields(extra, p.type());
            if (extra == null) { params.add(p); continue; }
            List<AnnotationInfo> merged = new ArrayList<>(p.annotations());
            for (AnnotationInfo a : extra) {
                // a declared annotation of the same kind wins over the model's guess
                if (merged.stream().noneMatch(d -> d.simpleName().equals(a.simpleName()))) merged.add(a);
            }
            params.add(new Parameter(p.index(), p.name(), p.type(), merged));
        }
        synchronized (this) { augmented++; }
        return new MethodTarget(m.declaringClass(), m.name(), m.descriptor(), m.access(), m.typeParams(), params, m.returnType(),
                m.thrownExceptions(), returns, m.firstLine());
    }

    @Override public void close() { pool.shutdownNow(); }
}
