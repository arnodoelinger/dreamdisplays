package com.dreamdisplays.api.addon

/**
 * Client: called for every display that exists and every one that appears later.
 *
 * The method takes a `Display`.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnDisplay

/**
 * Client: called when any display starts playing.
 *
 * The method takes `(Display)` or `(Display, DisplayState.Playing)`.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnPlaying

/**
 * Client: called when a display fails to load or play.
 *
 * The method takes `(Display)` or `(Display, DreamMediaException)`.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnFailed

/**
 * Server: called when a display is created.
 *
 * The method takes a `ServerDisplay`.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnDisplayCreated

/**
 * Server: called when a display is removed.
 *
 * The method takes a `ServerDisplay`.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnDisplayRemoved

/**
 * Server: called when a display's video link changes.
 *
 * The method takes `(ServerDisplay)` or `(ServerDisplay, String)` with the new link.
 *
 * @since 1.10.x
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@MustBeDocumented
annotation class OnUrlChanged
