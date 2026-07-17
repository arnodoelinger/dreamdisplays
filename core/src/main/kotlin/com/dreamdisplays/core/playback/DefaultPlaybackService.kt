package com.dreamdisplays.core.playback

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackPort
import com.dreamdisplays.api.playback.PlaybackService
import com.dreamdisplays.api.media.VideoQuality
import kotlin.time.Duration

/**
 * Default core implementation of [PlaybackService].
 */
class DefaultPlaybackService(
    private val playback: PlaybackPort,
) : PlaybackService {
    /** Get the playback state for the display with the given [id], or null if it doesn't exist. */
    override fun play(displayId: DisplayId) = playback.play(displayId)

    /** Pause the playback for the display with the given [id], or null if it doesn't exist. */
    override fun pause(displayId: DisplayId) = playback.pause(displayId)

    /** Stop the playback for the display with the given [id], or null if it doesn't exist. */
    override fun stop(displayId: DisplayId) = playback.stop(displayId)

    /** Seek to a specific position in the playback for the display with the given [id], or null if it doesn't exist. */
    override fun seek(displayId: DisplayId, position: Duration) = playback.seek(displayId, position)

    /** Seek [delta] relative to the current position for [displayId] (negative = backward). */
    override fun seekRelative(displayId: DisplayId, delta: Duration) = playback.seekRelative(displayId, delta)

    /** Set the volume for a display. */
    override fun setVolume(displayId: DisplayId, volume: Float) = playback.setVolume(displayId, volume)

    /** Set the preferred video quality for a display. */
    override fun setQuality(displayId: DisplayId, quality: VideoQuality) = playback.setQuality(displayId, quality)

    /** Set the active audio track for a display. */
    override fun setAudioTrack(displayId: DisplayId, trackUrl: String) = playback.setAudioTrack(displayId, trackUrl)

    /** Set the brightness multiplier for a display. */
    override fun setBrightness(displayId: DisplayId, brightness: Float) = playback.setBrightness(displayId, brightness)

    /** Mute or unmute the audio for a display. */
    override fun mute(displayId: DisplayId, muted: Boolean) = playback.mute(displayId, muted)

    /** Get the runtime state for a display. */
    override fun getState(displayId: DisplayId): DisplayRuntimeState = playback.getState(displayId)

    /** Restart the playback for a display. */
    override fun restart(displayId: DisplayId) = playback.restart(displayId)

    /** The effective [PlaybackMode] of a display (`WATCH_PARTY` while a session is live). */
    override fun getMode(displayId: DisplayId): PlaybackMode = playback.getMode(displayId)

    /** Requests a new persistent base mode (`LOCAL` / `SYNCED` / `BROADCAST`); the server validates it. */
    override fun setMode(displayId: DisplayId, mode: PlaybackMode) = playback.setMode(displayId, mode)

    /** Re-resolves and reloads the current video for [displayId] after a load failure (local recovery). */
    override fun retry(displayId: DisplayId) = playback.retry(displayId)
}
