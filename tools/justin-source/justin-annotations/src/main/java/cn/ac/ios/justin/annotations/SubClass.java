package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The runtime type of the object (or of the selected element / field) must be this class or a subtype of it. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface SubClass {
    Class<?> value();
}
