package com.dreamdisplays.api.playback.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.media.model.VideoQuality
import kotlin.time.Duration

/**
 * Playback port.
 *
 * @since 1.8.x
 */
@Unstable
interface PlaybackPort {
    /** Plays the display with the given [displayId]. */
    fun play(displayId: DisplayId)

    /** Pauses the display with the given [displayId]. */
    fun pause(displayId: DisplayId)

    /** Stops the display with the given [displayId]. */
    fun stop(displayId: DisplayId)

    /** Seeks to a specific position in the display with the given [displayId]. */
    fun seek(displayId: DisplayId, position: Duration)

    /** Seeks [delta] relative to the current position for [displayId] (negative = backward). */
    fun seekRelative(displayId: DisplayId, delta: Duration)

    /** Sets the volume for [displayId]. */
    fun setVolume(displayId: DisplayId, volume: Float)

    /** Sets the preferred video quality for [displayId]. */
    fun setQuality(displayId: DisplayId, quality: VideoQuality)

    /** Sets the active audio track for [displayId], identified by its resolved stream URL. */
    fun setAudioTrack(displayId: DisplayId, trackUrl: String)

    /** Sets the subtitle track language for [displayId], or null to turn subtitles off. Client-local, per-viewer. */
    fun setSubtitleTrack(displayId: DisplayId, lang: String?)

    /** Sets the brightness multiplier for a display. */
    fun setBrightness(displayId: DisplayId, brightness: Float)

    /** Mute or unmute the audio for a display. */
    fun mute(displayId: DisplayId, muted: Boolean)

    /** Gets the runtime state for a display. */
    fun getState(displayId: DisplayId): DisplayState

    /** Restarts the video for a display. */
    fun restart(displayId: DisplayId)

    /** The effective [PlaybackMode] of a display (`WATCH_PARTY` while a session is live). */
    fun getMode(displayId: DisplayId): PlaybackMode

    /** Requests a new persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates it. */
    fun setMode(displayId: DisplayId, mode: PlaybackMode)

    /** Re-resolves and reloads the current video for [displayId] after a load failure (local recovery). */
    fun retry(displayId: DisplayId)
}
