package org.example.proect.lavka.utils;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE}) // было только METHOD
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryLabel {
    String value();
}