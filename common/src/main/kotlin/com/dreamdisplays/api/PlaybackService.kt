package com.dreamdisplays.api

import kotlin.time.Duration

/**
 * Service for controlling playback of displays.
 *
 * @since 1.8.0
 */
interface PlaybackService {
    /** Plays the video for [displayId]. */
    fun play(displayId: DisplayId)

    /** Pauses the video for [displayId]. */
    fun pause(displayId: DisplayId)

    /** Stops the video for [displayId]. */
    fun stop(displayId: DisplayId)

    /** Seeks to a specific position in the video for [displayId]. */
    fun seek(displayId: DisplayId, position: Duration)

    /** Sets the volume for [displayId]. */
    fun setVolume(displayId: DisplayId, volume: Float)

    /** Mutes or unmutes the audio for [displayId]. */
    fun mute(displayId: DisplayId, muted: Boolean)

    /** Gets the runtime state for [displayId]. */
    fun getState(displayId: DisplayId): DisplayRuntimeState

    /** Restarts the video for [displayId]. */
    fun restart(displayId: DisplayId)
}
