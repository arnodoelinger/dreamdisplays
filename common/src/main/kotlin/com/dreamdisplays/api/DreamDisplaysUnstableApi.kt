package com.dreamdisplays.api

/**
 * Marks an API as unstable. It may change incompatibly or be removed in any release without a
 * major version bump. Callers must opt in explicitly with `@OptIn(DreamDisplaysUnstableApi::class)`.
 *
 * Dream Displays will provide a stable API in 2.0.0 version.
 *
 * @since 1.8.0
 */
@RequiresOptIn(
    message = "This API is unstable and may change or be removed.",
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
