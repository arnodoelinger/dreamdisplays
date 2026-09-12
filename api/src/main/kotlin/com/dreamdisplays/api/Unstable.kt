package com.dreamdisplays.api

/**
 * Marks an API as unstable; may change or be removed without major version bump.
 *
 * @since 1.8.x
 */
@RequiresOptIn(
    message = "Dream Displays API is unstable. Please use with caution and aware of potential breaking changes.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.TYPEALIAS,
)

annotation class Unstable
