package com.ldoc.model;

/**
 * Source-level visibility of a method, derived from its declared access modifier.
 * Tool descriptors are generated only for {@link #PUBLIC} methods — the descriptor's
 * purpose is "can a coding agent call this from code it is writing?", and non-public
 * methods are not accessible outside their declaring scope.
 */
public enum Visibility {
    PUBLIC,
    PROTECTED,
    PACKAGE_PRIVATE,
    PRIVATE
}
