package com.dreamdisplays.application.display

import com.dreamdisplays.api.PlaybackService
import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.core.display.DisplayRuntimeState
import com.dreamdisplays.core.playback.PlaybackMode
import kotlin.time.Duration

/**
 * Default application implementation of [PlaybackService].
 */
class DefaultPlaybackService(
    private val playback: PlaybackPort,
) : PlaybackService {
    override fun play(displayId: DisplayId) = playback.play(displayId)

    override fun pause(displayId: DisplayId) = playback.pause(displayId)

    override fun stop(displayId: DisplayId) = playback.stop(displayId)

    override fun seek(displayId: DisplayId, position: Duration) = playback.seek(displayId, position)

    override fun setVolume(displayId: DisplayId, volume: Float) = playback.setVolume(displayId, volume)

    override fun mute(displayId: DisplayId, muted: Boolean) = playback.mute(displayId, muted)

    override fun getState(displayId: DisplayId): DisplayRuntimeState =
        playback.getState(displayId)

    override fun restart(displayId: DisplayId) = playback.restart(displayId)

    override fun getMode(displayId: DisplayId): PlaybackMode =
        playback.getMode(displayId)

    override fun setMode(displayId: DisplayId, mode: PlaybackMode) = playback.setMode(displayId, mode)
}
