package com.dreamdisplays.api

import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.toRuntimeState
import kotlin.time.Duration

/**
 * Default [PlaybackService] backed by [DisplayRegistry] and the [com.dreamdisplays.displays.DisplayScreen] API.
 *
 * @since 1.8.0
 */
class DefaultPlaybackService : PlaybackService {
    /** Plays the video for [displayId]. */
    override fun play(displayId: DisplayId) {
        DisplayRegistry.screens[displayId.uuid]?.setPaused(false)
    }

    /** Pauses the video for [displayId]. */
    override fun pause(displayId: DisplayId) {
        DisplayRegistry.screens[displayId.uuid]?.setPaused(true)
    }

    /** Stops the video for [displayId]. */
    override fun stop(displayId: DisplayId) {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return
        DisplayRegistry.unregisterScreen(screen)
    }

    /** Seeks the video for [displayId] to [position]. */
    override fun seek(displayId: DisplayId, position: Duration) {
        DisplayRegistry.screens[displayId.uuid]?.seekToMillis(position.inWholeMilliseconds)
    }

    /** Sets the volume for [displayId] to [volume], a float between 0 and 1. */
    override fun setVolume(displayId: DisplayId, volume: Float) {
        DisplayRegistry.screens[displayId.uuid]?.let { it.volume = volume }
    }

    /** Mutes the video for [displayId] to [muted]. */
    override fun mute(displayId: DisplayId, muted: Boolean) {
        DisplayRegistry.screens[displayId.uuid]?.mute(muted)
    }

    /** Returns the current playback state for [displayId]. */
    override fun getState(displayId: DisplayId): DisplayRuntimeState =
        DisplayRegistry.screens[displayId.uuid]?.toRuntimeState() ?: DisplayRuntimeState.OutOfRange

    /** Restarts the video for [displayId]. */
    override fun restart(displayId: DisplayId) {
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return
        val url = screen.videoUrl ?: return
        screen.loadVideo(url, screen.lang ?: "")
    }
}
