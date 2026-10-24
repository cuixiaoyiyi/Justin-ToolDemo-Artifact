package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Structural annotation: the constraint annotations that follow it apply to the element at this index
 * of the array or list, e.g. {@code @Element(0) @Min(1) int[] sizes}. A later {@code @Element} starts
 * a sibling element.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface Element {
    int value();
}
