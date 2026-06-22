package com.dreamdisplays.api

/**
 * Marks an API as unstable. It may change incompatibly or be removed in any release without a
 * major version bump. Callers must opt in explicitly with `@OptIn(DreamDisplaysUnstableApi::class)`.
 *
 * Stable APIs are not annotated with this marker.
 *
 * @since 1.8.0
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

annotation class DreamDisplaysUnstableApi
