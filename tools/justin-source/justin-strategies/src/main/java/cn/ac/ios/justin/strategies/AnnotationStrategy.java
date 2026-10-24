package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.target.Parameter;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.type.TypeRef.ClassRef;
import cn.ac.ios.justin.core.type.TypeRef.Kind;
import cn.ac.ios.justin.core.type.TypeRef.Primitive;
import cn.ac.ios.justin.core.value.Candidate;
import cn.ac.ios.justin.core.value.Value;
import cn.ac.ios.justin.core.value.Value.NullValue;
import cn.ac.ios.justin.core.value.Value.ObjectValue;
import cn.ac.ios.justin.core.value.Value.PrimitiveValue;
import cn.ac.ios.justin.core.value.Value.Recipe;
import cn.ac.ios.justin.core.value.ValuePool;
import cn.ac.ios.justin.core.contract.Constraints;
import cn.ac.ios.justin.core.contract.Constraints.Spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * S3: values derived from annotations. Every annotation on a parameter — Bean Validation
 * (javax/jakarta), Hibernate Validator, JSR-305/JetBrains/Spring nullability, Justin's own basis
 * (Min, Max, Pattern, Nullable, ArrayPara, Field, Element, SubClass, InRange, Examples, IndexOf) and
 * the virtual annotations inferred by the model — is unified by {@link Constraints} into one spec,
 * and {@link SpecGenerator} turns the spec into candidates partitioned into bounds, inside and
 * outside values (JNN's I_bound, I_in, I_out). Field annotations of the classes being instantiated
 * constrain the objects built for object-typed parameters.
 */
public final class AnnotationStrategy implements Strategy {
    public static final String NAME = "annotation";
    /** Strategy name reported for candidates that come from model-inferred (virtual) annotations. */
    public static final String LLM_NAME = "llm";
    /** Binary-name prefix the LLM module uses for virtual annotations. */
    public static final String LLM_PREFIX = "justin.llm.";

    @Override public String name() { return NAME; }

    @Override
    public List<ValuePool> generate(GenerationContext ctx) {
        MethodTarget m = ctx.target();
        List<ValuePool> pools = new ArrayList<>();
        Random rnd = new Random(ctx.config().seed() ^ m.id().hashCode());
        if (!m.isStatic() && !m.isConstructor()) {
            ValuePool recv = new ValuePool(-1);
            new ValueGenerator(ctx).generate(ctx.owner().typeParams().isEmpty() ? ctx.owner().asType()
                    : new ClassRef(ctx.owner().binaryName(), ctx.owner().typeParams().stream().map(TypeRef.class::cast).toList()))
                    .filter(v -> !(v instanceof NullValue)).ifPresent(v -> recv.add(Candidate.legal(v, NAME)));
            pools.add(recv);
        }
        for (Parameter p : m.params()) {
            ValuePool pool = new ValuePool(p.index());
            TypeRef t = ctx.concreteType(p.type());
            Spec spec = Constraints.of(p.annotations());
            // candidates of a spec that only the model contributed are attributed to "llm"; declared annotations win the label
            String label = spec.fromModel && !spec.declared ? LLM_NAME : NAME;
            SpecGenerator gen = new SpecGenerator(ctx, rnd, label);
            for (Candidate c : gen.candidates(spec, t)) {
                pool.add(label.equals(LLM_NAME) ? new Candidate(c.value(), LLM_NAME, c.legal(), c.note() == null ? "inferred by the model" : c.note() + " (inferred by the model)") : c);
            }
            pools.add(pool);
        }
        return pools;
    }

    /** The unified constraints of a parameter, for callers that need the spec rather than values. */
    public static Spec specOf(Parameter p) { return Constraints.of(p.annotations()); }

    /** A numeric value of the parameter's type; Long.MIN_VALUE / MAX_VALUE mean the type's extremes; others outside the type are absent. */
    static Optional<Value> numeric(TypeRef t, long value) {
        String table = SpecialValues.tableFor(t);
        Kind k = Kind.fromKeyword(table);
        if (k == null) return Optional.empty();
        PrimitiveValue p;
        boolean extreme = value == Long.MIN_VALUE || value == Long.MAX_VALUE;
        switch (k) {
            case INT:
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) { if (!extreme) return Optional.empty(); value = value < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE; }
                p = PrimitiveValue.of((int) value); break;
            case LONG: p = PrimitiveValue.of(value); break;
            case SHORT:
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) { if (!extreme) return Optional.empty(); value = value < 0 ? Short.MIN_VALUE : Short.MAX_VALUE; }
                p = PrimitiveValue.of((short) value); break;
            case BYTE:
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) { if (!extreme) return Optional.empty(); value = value < 0 ? Byte.MIN_VALUE : Byte.MAX_VALUE; }
                p = PrimitiveValue.of((byte) value); break;
            case DOUBLE: p = PrimitiveValue.of((double) value); break;
            case FLOAT: p = PrimitiveValue.of((float) value); break;
            case CHAR:
                if (value < 0 || value > Character.MAX_VALUE) { if (!extreme) return Optional.empty(); value = value < 0 ? 0 : Character.MAX_VALUE; }
                p = PrimitiveValue.of((char) value); break;
            default: return Optional.empty();
        }
        if (t instanceof Primitive) return Optional.of(p);
        return Optional.of(new ObjectValue(ClassRef.of(k.boxed), new Recipe.Boxed(p)));
    }
}
