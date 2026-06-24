package com.dreamdisplays.api.platform

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Minimal structured logger contract used by modules that must not depend on a concrete backend.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface PlatformLogger {
    /** Logs an informational message. */
    fun info(message: String)

    /** Logs a warning message. */
    fun warn(message: String)

    /** Logs an error message with an optional [cause]. */
    fun error(message: String, cause: Throwable? = null)

    /** Logs a debug message. */
    fun debug(message: String)

    /** Returns a child logger nested under [name]. */
    fun child(name: String): PlatformLogger
}
