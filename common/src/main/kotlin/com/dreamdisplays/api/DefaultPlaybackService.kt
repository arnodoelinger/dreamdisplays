package com.dreamdisplays.api

import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.toRuntimeState
import kotlin.time.Duration

/**
 * Default [PlaybackService] backed by [DisplayManager] and the [com.dreamdisplays.display.DisplayScreen] API.
 *
 * @since 1.8.0
 */
class DefaultPlaybackService : PlaybackService {
    /** Plays the video for [displayId]. */
    override fun play(displayId: DisplayId) {
        DisplayManager.screens[displayId.uuid]?.setPaused(false)
    }

    /** Pauses the video for [displayId]. */
    override fun pause(displayId: DisplayId) {
        DisplayManager.screens[displayId.uuid]?.setPaused(true)
    }

    /** Stops the video for [displayId]. */
    override fun stop(displayId: DisplayId) {
        val screen = DisplayManager.screens[displayId.uuid] ?: return
        DisplayManager.unregisterScreen(screen)
    }

    /** Seeks the video for [displayId] to [position]. */
    override fun seek(displayId: DisplayId, position: Duration) {
        DisplayManager.screens[displayId.uuid]?.seekToMillis(position.inWholeMilliseconds)
    }

    /** Sets the volume for [displayId] to [volume], a float between 0 and 1. */
    override fun setVolume(displayId: DisplayId, volume: Float) {
        DisplayManager.screens[displayId.uuid]?.let { it.volume = volume }
    }

    /** Mutes the video for [displayId] to [muted]. */
    override fun mute(displayId: DisplayId, muted: Boolean) {
        DisplayManager.screens[displayId.uuid]?.mute(muted)
    }

    /** Returns the current playback state for [displayId]. */
    override fun getState(displayId: DisplayId): DisplayRuntimeState =
        DisplayManager.screens[displayId.uuid]?.toRuntimeState() ?: DisplayRuntimeState.OutOfRange

    /** Restarts the video for [displayId]. */
    override fun restart(displayId: DisplayId) {
        val screen = DisplayManager.screens[displayId.uuid] ?: return
        val url = screen.videoUrl ?: return
        screen.loadVideo(url, screen.lang ?: "")
    }
}
