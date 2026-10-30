package com.dreamdisplays.core.services

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.display.model.DisplayInfo
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode

/** Dispatcher for display commands. */
interface DisplayExecutor {
    /** Updates the display settings for [id]. */
    fun updateSettings(id: DisplayId, settings: DisplaySettings): DisplayInfo? = null

    /** Sets the URL for [id]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null): DisplayInfo? = null

    /** Sets who may use [id]. */
    fun setAccess(id: DisplayId, access: DisplayAccess): DisplayInfo? = null

    /** Deletes [id]. */
    fun delete(id: DisplayId): Boolean = false

    /** Reports [id] for moderation review. */
    fun report(id: DisplayId): DisplayInfo? = null

    /** Play. */
    fun play(displayId: DisplayId): DisplayInfo? = null

    /** Pause playback. */
    fun pause(displayId: DisplayId): DisplayInfo? = null

    /** Stop playback. */
    fun stop(displayId: DisplayId): Boolean = false

    /** Seek to a specific position. */
    fun seek(displayId: DisplayId, positionMs: Long): DisplayInfo? = null

    /** Seek [deltaMs] relative to the current position. */
    fun seekRelative(displayId: DisplayId, deltaMs: Long): DisplayInfo? = null

    /** Set the volume. */
    fun setVolume(displayId: DisplayId, volume: Float): DisplayInfo? = null

    /** Set the preferred video quality. */
    fun setQuality(displayId: DisplayId, quality: VideoQuality): DisplayInfo? = null

    /** Set the active audio track, identified by its resolved stream URL. */
    fun setAudioTrack(displayId: DisplayId, trackUrl: String): DisplayInfo? = null

    /** Sets the subtitle track language, or `null` to turn subtitles off. */
    fun setSubtitleTrack(displayId: DisplayId, lang: String?): DisplayInfo? = null

    /** Set the brightness multiplier. */
    fun setBrightness(displayId: DisplayId, brightness: Float): DisplayInfo? = null

    /** Mute or unmute the audio. */
    fun mute(displayId: DisplayId, muted: Boolean): DisplayInfo? = null

    /** Restart playback. */
    fun restart(displayId: DisplayId): DisplayInfo? = null

    /** Set the playback mode. */
    fun setMode(displayId: DisplayId, mode: PlaybackMode): DisplayInfo? = null

    /** Re-resolves and reloads the current video after a load failure (local recovery). */
    fun retry(displayId: DisplayId): DisplayInfo? = null

    /** Start a `Watch party` session. */
    fun startWatchParty(displayId: DisplayId, url: String?): DisplayInfo? = null

    /** Set the ready state for a `Watch party` session. */
    fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): DisplayInfo? = null

    /** Start a `Watch party` session. */
    fun beginWatchParty(displayId: DisplayId): DisplayInfo? = null

    /** End a `Watch party` session. */
    fun endWatchParty(displayId: DisplayId): DisplayInfo? = null

    /** Restart a `Watch party` session. */
    fun restartWatchParty(displayId: DisplayId): DisplayInfo? = null

    /** Close a `Watch party` session. */
    fun closeWatchParty(displayId: DisplayId): DisplayInfo? = null

    companion object {
        val Noop: DisplayExecutor = object : DisplayExecutor {}
    }
}
