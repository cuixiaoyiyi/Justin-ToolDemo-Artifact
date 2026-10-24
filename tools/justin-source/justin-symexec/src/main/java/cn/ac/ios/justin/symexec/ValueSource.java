package cn.ac.ios.justin.symexec;

import cn.ac.ios.justin.core.type.TypeRef;
import cn.ac.ios.justin.core.value.Value;

/**
 * What the executor asks of the value generator when it turns a model into inputs: values for what the model
 * does not describe (the elements of a collection that are objects), and the types behind type variables.
 */
public interface ValueSource {

    /** Any value of the type, or null when none can be made. */
    Value generate(TypeRef type);

    /** The type with the type variables of the method and its class replaced by what they are bound to. */
    TypeRef concrete(TypeRef declared);

    /** Makes nothing and leaves types as they are. */
    ValueSource NONE = new ValueSource() {
        @Override public Value generate(TypeRef type) { return null; }
        @Override public TypeRef concrete(TypeRef declared) { return declared; }
    };
}
