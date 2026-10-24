package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.config.GenerationConfig;
import cn.ac.ios.justin.core.target.ClassTarget;
import cn.ac.ios.justin.core.target.MethodTarget;
import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.generics.TypeInstantiator;

import java.util.Map;
import java.util.Random;

/**
 * Per-method context handed to strategies. The Random is derived from the global seed and the method
 * id, so a method's inputs are reproducible regardless of processing order or parallelism.
 */
public final class GenerationContext {
    private final GenerationConfig config;
    private final ClassTarget owner;
    private final MethodTarget target;
    private final TypeInfoProvider types;
    private final Random random;
    private final TypeInstantiator instantiator;
    private final Map<String, TypeRef> typeBindings;
    private final cn.ac.ios.justin.core.test.BodyFacts facts;

    public GenerationContext(GenerationConfig config, ClassTarget owner, MethodTarget target, TypeInfoProvider types, Map<String, TypeRef> typeBindings) {
        this(config, owner, target, types, typeBindings, cn.ac.ios.justin.core.test.BodyFacts.NONE);
    }

    public GenerationContext(GenerationConfig config, ClassTarget owner, MethodTarget target, TypeInfoProvider types, Map<String, TypeRef> typeBindings,
                             cn.ac.ios.justin.core.test.BodyFacts facts) {
        this.config = config;
        this.owner = owner;
        this.target = target;
        this.types = types;
        this.random = new Random(config.seed() * 31 + target.id().hashCode());
        this.instantiator = new TypeInstantiator(new Random(random.nextLong()), new ProjectTypeKnowledge(types));
        this.typeBindings = Map.copyOf(typeBindings);
        this.facts = facts == null ? cn.ac.ios.justin.core.test.BodyFacts.NONE : facts;
    }

    /** Body facts from the analysis layer (constants, relations); NONE when unavailable. */
    public cn.ac.ios.justin.core.test.BodyFacts facts() { return facts; }

    public GenerationConfig config() { return config; }
    public ClassTarget owner() { return owner; }
    public MethodTarget target() { return target; }
    public TypeInfoProvider types() { return types; }
    public Random random() { return random; }
    public TypeInstantiator instantiator() { return instantiator; }

    /** Bindings for class-level and method-level type variables chosen for this method. */
    public Map<String, TypeRef> typeBindings() { return typeBindings; }

    /** Fully concrete type of a parameter after substitution and wildcard removal. */
    public TypeRef concreteType(TypeRef declared) { return instantiator.concretize(declared, typeBindings); }
}
