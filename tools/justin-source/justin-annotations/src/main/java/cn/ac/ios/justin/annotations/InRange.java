package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Legal values lie in [min, max] (inclusive). Values outside are contract violations. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface InRange {
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
}
