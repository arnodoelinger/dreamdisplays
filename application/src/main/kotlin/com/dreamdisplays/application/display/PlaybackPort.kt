package com.dreamdisplays.application.display

import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplayRuntimeState
import com.dreamdisplays.core.playback.PlaybackMode
import kotlin.time.Duration

interface PlaybackPort {
    fun play(displayId: DisplayId)
    fun pause(displayId: DisplayId)
    fun stop(displayId: DisplayId)
    fun seek(displayId: DisplayId, position: Duration)
    fun setVolume(displayId: DisplayId, volume: Float)
    fun mute(displayId: DisplayId, muted: Boolean)
    fun getState(displayId: DisplayId): DisplayRuntimeState
    fun restart(displayId: DisplayId)
    fun getMode(displayId: DisplayId): PlaybackMode
    fun setMode(displayId: DisplayId, mode: PlaybackMode)
}
