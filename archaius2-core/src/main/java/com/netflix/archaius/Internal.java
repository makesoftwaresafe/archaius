package com.netflix.archaius;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;

/**
 * Annotation to convey classes and other elements not meant to be part of the public API, but are public by necessity;
 * such elements should not be depended on by consumers as there is no guarantee of API stability.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = {CONSTRUCTOR, METHOD, TYPE, FIELD, PACKAGE})
public @interface Internal {
}