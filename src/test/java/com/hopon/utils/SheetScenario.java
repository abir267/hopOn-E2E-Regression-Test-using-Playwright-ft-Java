package com.hopon.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test method to a row in the Scenario tab (Feature + Scenario columns).
 * If the row doesn't exist yet, it is added automatically.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SheetScenario {
    String feature();
    String scenario();
}
