package com.dreamdisplays.api.playback

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.media.VideoQuality
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

    /** Seeks [delta] relative to the current position for [displayId] (negative = backward). */
    fun seekRelative(displayId: DisplayId, delta: Duration)

    /** Sets the volume for [displayId]. */
    fun setVolume(displayId: DisplayId, volume: Float)

    /** Sets the preferred video quality for [displayId]. */
    fun setQuality(displayId: DisplayId, quality: VideoQuality)

    /** Sets the brightness multiplier (0.0–2.0) for [displayId]. */
    fun setBrightness(displayId: DisplayId, brightness: Float)

    /** Mutes or unmutes the audio for [displayId]. */
    fun mute(displayId: DisplayId, muted: Boolean)

    /** Gets the runtime state for [displayId]. */
    fun getState(displayId: DisplayId): DisplayRuntimeState

    /** Restarts the video for [displayId]. */
    fun restart(displayId: DisplayId)

    /** The effective [PlaybackMode] of [displayId] (`WATCH_PARTY` while a session is live). */
    fun getMode(displayId: DisplayId): PlaybackMode

    /** Requests a new persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates it. */
    fun setMode(displayId: DisplayId, mode: PlaybackMode)
}
