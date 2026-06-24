package com.dreamdisplays.core.playback

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.display.model.DisplayRuntimeState
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.media.VideoQuality
import kotlin.time.Duration

interface PlaybackPort {
    fun play(displayId: DisplayId)
    fun pause(displayId: DisplayId)
    fun stop(displayId: DisplayId)
    fun seek(displayId: DisplayId, position: Duration)
    fun seekRelative(displayId: DisplayId, delta: Duration)
    fun setVolume(displayId: DisplayId, volume: Float)
    fun setQuality(displayId: DisplayId, quality: VideoQuality)
    fun setBrightness(displayId: DisplayId, brightness: Float)
    fun mute(displayId: DisplayId, muted: Boolean)
    fun getState(displayId: DisplayId): DisplayRuntimeState
    fun restart(displayId: DisplayId)
    fun getMode(displayId: DisplayId): PlaybackMode
    fun setMode(displayId: DisplayId, mode: PlaybackMode)
    fun retry(displayId: DisplayId)
}
