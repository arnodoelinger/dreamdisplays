package com.dreamdisplays.api.display.service

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.Display
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.display.model.settings.DisplaySettings
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import kotlin.time.Duration

/**
 * Dispatcher for display commands.
 *
 * @since 1.8.x
 */
@Unstable
interface DisplayExecutor {
    /** Updates the display settings for [id]. */
    fun updateSettings(id: DisplayId, settings: DisplaySettings): Display? = null

    /** Sets the URL for [id]. */
    fun setUrl(id: DisplayId, url: String?, lang: String? = null): Display? = null

    /** Sets who may use [id]. */
    fun setAccess(id: DisplayId, access: DisplayAccess): Display? = null

    /** Deletes [id]. */
    fun delete(id: DisplayId): Boolean = false

    /** Reports [id] for moderation review. */
    fun report(id: DisplayId): Display? = null

    /** Play. */
    fun play(displayId: DisplayId): Display? = null

    /** Pause playback. */
    fun pause(displayId: DisplayId): Display? = null

    /** Stop playback. */
    fun stop(displayId: DisplayId): Boolean = false

    /** Seek to a specific position. */
    fun seek(displayId: DisplayId, position: Duration): Display? = null

    /** Seek [delta] relative to the current position. */
    fun seekRelative(displayId: DisplayId, delta: Duration): Display? = null

    /** Set the volume. */
    fun setVolume(displayId: DisplayId, volume: Float): Display? = null

    /** Set the preferred video quality. */
    fun setQuality(displayId: DisplayId, quality: VideoQuality): Display? = null

    /** Set the active audio track, identified by its resolved stream URL. */
    fun setAudioTrack(displayId: DisplayId, trackUrl: String): Display? = null

    /** Sets the subtitle track language, or null to turn subtitles off. */
    fun setSubtitleTrack(displayId: DisplayId, lang: String?): Display? = null

    /** Set the brightness multiplier. */
    fun setBrightness(displayId: DisplayId, brightness: Float): Display? = null

    /** Mute or unmute the audio. */
    fun mute(displayId: DisplayId, muted: Boolean): Display? = null

    /** Restart playback. */
    fun restart(displayId: DisplayId): Display? = null

    /** Set the playback mode. */
    fun setMode(displayId: DisplayId, mode: PlaybackMode): Display? = null

    /** Re-resolves and reloads the current video after a load failure (local recovery). */
    fun retry(displayId: DisplayId): Display? = null

    /** Start a watch party session. */
    fun startWatchParty(displayId: DisplayId, url: String?): Display? = null

    /** Set the ready state for a watch party session. */
    fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): Display? = null

    /** Start a watch party session. */
    fun beginWatchParty(displayId: DisplayId): Display? = null

    /** End a watch party session. */
    fun endWatchParty(displayId: DisplayId): Display? = null

    /** Restart a watch party session. */
    fun restartWatchParty(displayId: DisplayId): Display? = null

    /** Close a watch party session. */
    fun closeWatchParty(displayId: DisplayId): Display? = null

    companion object {
        /** No-op implementation. */
        val Noop: DisplayExecutor = object : DisplayExecutor {}
    }
}
