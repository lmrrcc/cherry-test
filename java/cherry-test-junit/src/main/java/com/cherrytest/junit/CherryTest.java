package com.cherrytest.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Test
@Tag(CherryTest.TAG)
@ExtendWith(CherryTestExtension.class)
public @interface CherryTest {
    String TAG = "cherry-test-integration";

    String scope() default "";

    String auth() default "";
}
