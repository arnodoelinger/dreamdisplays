package com.dreamdisplays.platform.client.player.platform

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.VideoQuality
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.api.media.player.PlaybackHost
import java.util.UUID

/**
 * Adapts a Minecraft [DisplayScreen] to the platform-agnostic [PlaybackHost] the media player drives.
 * Keeps the player decoupled from the screen's full surface; all calls delegate to [screen].
 */
class DisplayPlaybackHost(private val screen: DisplayScreen) : PlaybackHost {
    /** The display's unique id. */
    override val uuid: UUID get() = screen.uuid

    /** The display's requested video quality. */
    override val quality: VideoQuality get() = screen.quality

    /** Current target texture width (pending texture during a quality handoff). */
    override val textureWidth: Int get() = screen.textureWidth

    /** Current target texture height (pending texture during a quality handoff). */
    override val textureHeight: Int get() = screen.textureHeight

    /** Whether playback is paused. */
    override val isPaused: Boolean get() = screen.isPaused

    /** The mode the player experiences (`WATCH_PARTY` while a session is live). */
    override val effectiveMode: PlaybackMode get() = screen.effectiveMode

    /** Aspect ratio of the decoded content, surfaced for popout sizing. */
    override var videoContentAspect: Double
        get() = screen.videoContentAspect
        set(value) {
            screen.videoContentAspect = value
        }

    /** The display's current media error, or `null` when healthy. */
    override var mediaError: DreamMediaException?
        get() = screen.mediaError
        set(value) {
            screen.mediaError = value
        }

    /** Notifies the screen that a user seek completed (emits the intent upstream). */
    override fun afterSeek() = screen.afterSeek()

    /** Stages a new-resolution texture for a quality switch. */
    override fun beginQualityHandoff() = screen.beginQualityHandoff()

    /** Drops a staged quality-handoff texture. */
    override fun cancelQualityHandoff() = screen.cancelQualityHandoff()

    /** Recreates the GPU texture (e.g. after a resolution change). */
    override fun reloadTexture() = screen.reloadTexture()

    /** Marks playback as ended at [positionNanos]. */
    override fun onPlaybackEnded(positionNanos: Long) = screen.onPlaybackEnded(positionNanos)
}
