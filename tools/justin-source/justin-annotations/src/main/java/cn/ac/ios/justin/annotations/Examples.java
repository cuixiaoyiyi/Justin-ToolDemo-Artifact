package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Literal example values worth trying for this parameter, written as Java literals would be parsed
 * by Justin's special-value table ("42", "-1", "\"text\"", "'c'", "3.5", "true").
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface Examples {
    String[] value();
    /** Values that violate the contract and should provoke an exception. */
    String[] invalid() default {};
}
