package com.dreamdisplays.api

/**
 * Outcome of a command sent to a [Display].
 *
 * @since 1.10.x
 */
enum class CommandResult {
    /** The request was accepted. */
    OK,

    /**
     * The display no longer exists, so nothing was done.
     *
     * Safe to ignore.
     */
    STALE,

    /**
     * The request was refused by the rules (for example a video link that is not allowed).
     *
     * Nothing was changed.
     */
    REFUSED;

    /** `true` when the request was accepted. */
    val isOk: Boolean get() = this == OK

    /** Runs [action] when the display no longer exists. */
    inline fun onStale(action: () -> Unit): CommandResult = also { if (this == STALE) action() }
}
