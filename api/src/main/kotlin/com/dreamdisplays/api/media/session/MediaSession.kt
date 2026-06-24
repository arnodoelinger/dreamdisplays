package com.dreamdisplays.api.media.session

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.media.source.MediaMetadata
import kotlin.time.Duration

/**
 * Live playback session for one display. Implementations own decoder / player resources; callers
 * control playback through this contract and close the session when the display stops using it.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface MediaSession : AutoCloseable {
    /** Stable session id used to correlate events and display runtime state. */
    val sessionId: String

    /** Display this session belongs to. */
    val displayId: DisplayId

    /** Current lifecycle state of the media session. */
    val state: MediaSessionState

    /** Current playback position in the resolved media timeline. */
    val currentPosition: Duration

    /** Total duration, or null for live / unknown-length media. */
    val duration: Duration?

    /** Metadata resolved for the current media. */
    val metadata: MediaMetadata

    /** Requests playback to start or resume. */
    fun play()

    /** Requests playback to pause at the current position. */
    fun pause()

    /** Seeks to [position] in the resolved media timeline. */
    fun seek(position: Duration)

    /** Sets the session volume multiplier. */
    fun setVolume(volume: Float)

    /** Subscribes [listener] to session events; close the returned handle to unsubscribe. */
    fun on(listener: (MediaSessionEvent) -> Unit): AutoCloseable
}
