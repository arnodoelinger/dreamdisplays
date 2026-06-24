package com.dreamdisplays.api.media.session

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.media.DreamMediaException

/**
 * Lifecycle state of a media session, from idle / resolving through active playback and teardown.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
sealed interface MediaSessionState {
    /** No media is currently loaded. */
    data object Idle : MediaSessionState

    /** Media is resolving or the player is preparing its first frame. */
    data object Preparing : MediaSessionState

    /** Media is loaded; [isPlaying] and [isBuffering] describe the current playback sub-state. */
    data class Active(val isPlaying: Boolean, val isBuffering: Boolean) : MediaSessionState

    /** Playback stalled and the session has retried [retryCount] times. */
    data class Stalled(val retryCount: Int) : MediaSessionState

    /** Playback failed with [cause]. */
    data class Error(val cause: DreamMediaException) : MediaSessionState

    /** Non-looping media reached its end. */
    data object Ended : MediaSessionState

    /** Session resources have been released and the session must not be reused. */
    data object Released : MediaSessionState

    /** True when the session cannot continue without a new media load. */
    val isTerminal: Boolean get() = this is Released || (this is Error && cause.isFatal)
}
