package com.dreamdisplays.media.runtime

import com.dreamdisplays.api.display.event.DisplayEvent
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.playback.PlaybackService
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MediaSession] view onto a display, expressed purely over the core services: transport calls
 * delegate to [PlaybackService], state and events come from [DisplayService] (snapshots + event bus),
 * filtered to this display. No Minecraft view object is referenced, so the session is platform-agnostic.
 * Closing the session only detaches listeners; playback is unaffected.
 */
internal class DisplayMediaSession(
    override val displayId: DisplayId,
    private val playback: PlaybackService,
    private val displays: DisplayService,
) : MediaSession {

    override val sessionId: String = displayId.toString()

    private val subscriptions = CopyOnWriteArrayList<AutoCloseable>()

    @Volatile
    private var closed = false

    /** The latest runtime state from the display snapshot, or null when the display is gone. */
    private fun runtimeState(): DisplayRuntimeState? = displays.getDisplay(displayId)?.state

    override val state: MediaSessionState
        get() = if (closed) MediaSessionState.Released
        else runtimeState()?.toSessionState() ?: MediaSessionState.Released

    override val currentPosition: Duration
        get() = when (val s = runtimeState()) {
            is DisplayRuntimeState.Playing -> s.positionMs.milliseconds
            is DisplayRuntimeState.Paused -> s.positionMs.milliseconds
            else -> Duration.ZERO
        }

    override val duration: Duration?
        get() = (runtimeState() as? DisplayRuntimeState.Playing)?.durationMs?.milliseconds

    /** Only the duration is known at this layer; rich metadata lives in the search/metadata caches. */
    override val metadata: MediaMetadata
        get() = MediaMetadata.UNKNOWN.copy(duration = duration)

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
    private fun DisplayRuntimeState.toSessionState(): MediaSessionState = when (this) {
        is DisplayRuntimeState.Idle -> MediaSessionState.Idle
        is DisplayRuntimeState.OutOfRange -> MediaSessionState.Released
        is DisplayRuntimeState.Preparing -> MediaSessionState.Preparing
        is DisplayRuntimeState.Buffering -> MediaSessionState.Active(isPlaying = false, isBuffering = true)
        is DisplayRuntimeState.Playing -> MediaSessionState.Active(isPlaying = true, isBuffering = false)
        is DisplayRuntimeState.Paused -> MediaSessionState.Active(isPlaying = false, isBuffering = false)
        is DisplayRuntimeState.Failed -> MediaSessionState.Error(cause)
        is DisplayRuntimeState.Stopped -> MediaSessionState.Ended
    }
}
