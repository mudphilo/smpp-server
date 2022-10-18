package io.github.mudphilo.jmx.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 
 * @author German Escobar
 */
@Documented
@Retention(value=RUNTIME)
@Target(value={METHOD})
public @interface ManagedOperation {
    Impact impact() default Impact.UNKNOWN;
    String description() default "";
}