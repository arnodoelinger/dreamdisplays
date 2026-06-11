package com.dreamdisplays.media

import com.dreamdisplays.api.DisplayEvent
import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.api.DisplayRuntimeState
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import com.dreamdisplays.media.api.MediaMetadata
import com.dreamdisplays.media.api.MediaSession
import com.dreamdisplays.media.api.MediaSessionEvent
import com.dreamdisplays.media.api.MediaSessionState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * [MediaSession] view onto a live [DisplayScreen]. Transport calls delegate to the screen's
 * public playback API; events are translated from the [DisplayRegistry] event bus, filtered to
 * this display. Closing the session only detaches listeners. Playback is unaffected.
 */
internal class DisplayMediaSession(private val screen: DisplayScreen) : MediaSession {

    override val sessionId: String = screen.uuid.toString()
    override val displayId: DisplayId = DisplayId(screen.uuid)

    private val subscriptions = CopyOnWriteArrayList<AutoCloseable>()

    @Volatile
    private var closed = false

    override val state: MediaSessionState
        get() = when {
            closed -> MediaSessionState.Released
            screen.mediaError != null -> MediaSessionState.Error(screen.mediaError!!)
            screen.videoUrl.isNullOrEmpty() -> MediaSessionState.Idle
            !screen.isVideoStarted -> MediaSessionState.Preparing
            else -> MediaSessionState.Active(isPlaying = !screen.isPaused, isBuffering = false)
        }

    override val currentPosition: Duration
        get() = screen.currentTimeNanos.nanoseconds

    override val duration: Duration?
        get() = screen.mediaPlayerDurationNanos.takeIf { it > 0L }?.nanoseconds

    /** Only the duration is known at this layer; rich metadata lives in the search/metadata caches. */
    override val metadata: MediaMetadata
        get() = MediaMetadata.UNKNOWN.copy(duration = duration)

    override fun play() = screen.setPaused(false)

    override fun pause() = screen.setPaused(true)

    override fun seek(position: Duration) = screen.seekToMillis(position.inWholeMilliseconds)

    override fun setVolume(volume: Float) {
        screen.volume = volume
    }

    /**
     * Subscribes [listener] to this display's lifecycle, translated into [MediaSessionEvent]s.
     * Close the returned handle (or the whole session) to unsubscribe.
     */
    override fun on(listener: (MediaSessionEvent) -> Unit): AutoCloseable {
        val handle = DisplayRegistry.addListener { event ->
            if (event.displayId != displayId) return@addListener
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
