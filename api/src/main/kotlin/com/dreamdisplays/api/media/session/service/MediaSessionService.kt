package com.dreamdisplays.api.media.session.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.media.session.event.MediaSessionEvent
import com.dreamdisplays.api.media.session.property.MediaSessionState
import com.dreamdisplays.api.media.source.model.MediaMetadata
import kotlin.time.Duration

/**
 * Live playback session for one display; owns decoder / player resources, closed by caller.
 *
 * @since 1.8.x
 */
@Unstable
interface MediaSessionService : AutoCloseable {
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
