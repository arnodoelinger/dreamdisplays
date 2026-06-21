package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.core.media.DreamMediaException
import com.dreamdisplays.core.media.VideoQuality
import com.dreamdisplays.core.playback.PlaybackMode
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.media.player.api.PlaybackHost
import java.util.UUID

/**
 * Adapts a Minecraft [DisplayScreen] to the platform-agnostic [PlaybackHost] the media player drives.
 * Keeps the player decoupled from the screen's full surface; all calls delegate to [screen].
 */
class DisplayPlaybackHost(private val screen: DisplayScreen) : PlaybackHost {
    override val uuid: UUID get() = screen.uuid
    override val quality: VideoQuality get() = screen.quality
    override val textureWidth: Int get() = screen.textureWidth
    override val textureHeight: Int get() = screen.textureHeight
    override val isPaused: Boolean get() = screen.isPaused
    override val effectiveMode: PlaybackMode get() = screen.effectiveMode

    override var videoContentAspect: Double
        get() = screen.videoContentAspect
        set(value) { screen.videoContentAspect = value }

    override var mediaError: DreamMediaException?
        get() = screen.mediaError
        set(value) { screen.mediaError = value }

    override fun afterSeek() = screen.afterSeek()
    override fun beginQualityHandoff() = screen.beginQualityHandoff()
    override fun cancelQualityHandoff() = screen.cancelQualityHandoff()
    override fun reloadTexture() = screen.reloadTexture()
    override fun onPlaybackEnded(positionNanos: Long) = screen.onPlaybackEnded(positionNanos)
}
