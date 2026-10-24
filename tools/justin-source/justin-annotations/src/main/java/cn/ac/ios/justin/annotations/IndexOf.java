package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This int parameter is an index into the array, List, CharSequence or other sized parameter named
 * by value(): legal values are 0 &lt;= index &lt; length.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface IndexOf {
    /** Name of the parameter being indexed (requires -parameters or debug info so names survive compilation). */
    String value();
}
