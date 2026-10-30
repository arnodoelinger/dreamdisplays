package com.dreamdisplays.api

/**
 * Marks an API as experimental; may change or be removed without major version bump.
 *
 * @since 1.8.x
 */
@RequiresOptIn(
    message = "This is experimental Dream Displays API. Please use it with caution.",
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

annotation class Experimental
