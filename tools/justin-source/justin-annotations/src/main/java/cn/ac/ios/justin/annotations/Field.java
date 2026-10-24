package cn.ac.ios.justin.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Structural annotation: the constraint annotations that follow it apply to the named field of the
 * object (or of the element selected by a preceding {@link Element}), e.g.
 * {@code @Field("age") @Min(5) @Max(22) Person p}. A later {@code @Field} starts a sibling field.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface Field {
    String value();
}
