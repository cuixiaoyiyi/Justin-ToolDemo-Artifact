package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The string must match this Java regular expression entirely. Test inputs are sampled from the language; return values are asserted against it. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface Pattern {
    String value();
}
