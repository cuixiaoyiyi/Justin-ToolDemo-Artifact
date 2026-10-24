package cn.ac.ios.justin.strategies;

import cn.ac.ios.justin.core.value.ValuePool;

import java.util.List;

/**
 * A value-pool strategy: contributes candidate values for every slot of the target (receiver = -1,
 * parameters = 0..n-1). Tuple-producing strategies (symbolic execution) implement TupleStrategy instead.
 */
public interface Strategy {
    String name();

    /** One pool per slot, in slot order (receiver first when the method is an instance method). Pools may be empty. */
    List<ValuePool> generate(GenerationContext ctx);
}
