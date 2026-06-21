package com.dreamdisplays.core.display

import com.dreamdisplays.media.VideoQuality
import com.dreamdisplays.core.playback.PlaybackMode
import kotlin.time.Duration

interface DisplayCommandExecutor {
    fun updateSettings(id: DisplayId, settings: DisplaySettings): Display? = null
    fun setUrl(id: DisplayId, url: String?): Display? = null

    fun play(displayId: DisplayId): Display? = null
    fun pause(displayId: DisplayId): Display? = null
    fun stop(displayId: DisplayId): Boolean = false
    fun seek(displayId: DisplayId, position: Duration): Display? = null
    fun seekRelative(displayId: DisplayId, delta: Duration): Display? = null
    fun setVolume(displayId: DisplayId, volume: Float): Display? = null
    fun setQuality(displayId: DisplayId, quality: VideoQuality): Display? = null
    fun setBrightness(displayId: DisplayId, brightness: Float): Display? = null
    fun mute(displayId: DisplayId, muted: Boolean): Display? = null
    fun restart(displayId: DisplayId): Display? = null
    fun setMode(displayId: DisplayId, mode: PlaybackMode): Display? = null

    fun startWatchParty(displayId: DisplayId, url: String?): Display? = null
    fun setWatchPartyReady(displayId: DisplayId, ready: Boolean): Display? = null
    fun beginWatchParty(displayId: DisplayId): Display? = null
    fun endWatchParty(displayId: DisplayId): Display? = null
    fun restartWatchParty(displayId: DisplayId): Display? = null
    fun closeWatchParty(displayId: DisplayId): Display? = null

    companion object {
        val Noop: DisplayCommandExecutor = object : DisplayCommandExecutor {}
    }
}
