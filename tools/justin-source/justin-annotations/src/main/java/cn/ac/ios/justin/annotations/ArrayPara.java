package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an integer parameter to the length of the array (or string / collection) parameter at index
 * {@code paraIndex} (0-based): flag 0 means this parameter equals the length, -1 means it is at most the
 * length (an index or a count), 1 means it is at least the length (a capacity).
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface ArrayPara {
    int paraIndex();
    int flag() default 0;
}
