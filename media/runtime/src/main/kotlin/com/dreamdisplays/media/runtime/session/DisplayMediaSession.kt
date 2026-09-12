package com.dreamdisplays.media.runtime.session

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.media.session.service.MediaSessionService
import com.dreamdisplays.api.media.session.event.MediaSessionEvent
import com.dreamdisplays.api.media.session.property.MediaSessionState
import com.dreamdisplays.api.media.source.model.MediaMetadata
import com.dreamdisplays.api.playback.service.PlaybackService
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MediaSessionService] view onto a display, expressed purely over the core services: transport calls delegate to [PlaybackService] and
 * state to [DisplayService].
 */
internal class DisplayMediaSession(
    override val displayId: DisplayId,
    private val playback: PlaybackService,
    private val displays: DisplayService,
) : MediaSessionService {
    /** Session ID is the display ID, since each display has at most one session. */
    override val sessionId: String = displayId.toString()

    /** All listeners registered through [on], so they can be detached when the session is closed. */
    private val subscriptions = CopyOnWriteArrayList<AutoCloseable>()

    /** True after [close] has been called, so the session is no longer valid. */
    @Volatile
    private var closed = false

    /** The latest runtime state from the display snapshot, or null when the display is gone. */
    private fun runtimeState(): DisplayState? = displays.getDisplay(displayId)?.state

    /**
     * The session state is derived from the display runtime state, or [MediaSessionState.Released] if the display is gone
     * or the session is closed.
     */
    override val state: MediaSessionState
        get() = if (closed) MediaSessionState.Released
        else runtimeState()?.toSessionState() ?: MediaSessionState.Released

    /**
     * The current position is derived from the display runtime state, or zero if the display is gone or the session is
     * closed.
     */
    override val currentPosition: Duration
        get() = when (val s = runtimeState()) {
            is DisplayState.Playing -> s.positionMs.milliseconds
            is DisplayState.Paused -> s.positionMs.milliseconds
            else -> Duration.ZERO
        }

    /** The duration is derived from the display runtime state, or null if the display is gone or the session is closed. */
    override val duration: Duration?
        get() = (runtimeState() as? DisplayState.Playing)?.durationMs?.milliseconds

    /** Only the duration is known at this layer; rich metadata lives in the search / metadata caches. */
    override val metadata: MediaMetadata
        get() = MediaMetadata.UNKNOWN.copy(duration = duration)

    /** Playback control calls are delegated to the [PlaybackService] with the display ID. */
    override fun play() = playback.play(displayId)
    override fun pause() = playback.pause(displayId)
    override fun seek(position: Duration) = playback.seek(displayId, position)
    override fun setVolume(volume: Float) = playback.setVolume(displayId, volume)

    /**
     * Subscribes [listener] to this display's lifecycle, translated into [MediaSessionEvent]s.
     * Close the returned handle (or the whole session) to unsubscribe.
     */
    override fun on(listener: (MediaSessionEvent) -> Unit): AutoCloseable {
        val handle = displays.on { event ->
            if (event.displayId != displayId) return@on
            event.toSessionEvent()?.let(listener)
        }
        subscriptions += handle
        return AutoCloseable {
            handle.close()
            subscriptions -= handle
        }
    }

    /** Detaches every listener registered through [on]. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        subscriptions.forEach { it.close() }
        subscriptions.clear()
    }

    /** Maps a display lifecycle event onto the session vocabulary; null for events sessions don't care about. */
    private fun DisplayEvent.toSessionEvent(): MediaSessionEvent? = when (this) {
        is DisplayEvent.StateChanged ->
            MediaSessionEvent.StateChanged(previous.toSessionState(), current.toSessionState())

        is DisplayEvent.MediaError -> MediaSessionEvent.Error(cause)
        is DisplayEvent.Removed -> MediaSessionEvent.Ended
        else -> null
    }

    /** Maps the display runtime state onto the session state machine. */
    private fun DisplayState.toSessionState(): MediaSessionState = when (this) {
        is DisplayState.Idle -> MediaSessionState.Idle
        is DisplayState.OutOfRange -> MediaSessionState.Released
        is DisplayState.Preparing -> MediaSessionState.Preparing
        is DisplayState.Buffering -> MediaSessionState.Active(isPlaying = false, isBuffering = true)
        is DisplayState.Playing -> MediaSessionState.Active(isPlaying = true, isBuffering = false)
        is DisplayState.Paused -> MediaSessionState.Active(isPlaying = false, isBuffering = false)
        is DisplayState.Failed -> MediaSessionState.Error(cause)
        is DisplayState.Stopped -> MediaSessionState.Ended
    }
}
