package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.api.playback.model.PlaybackMode
import java.util.*

/**
 * Display abstraction for [com.dreamdisplays.media.player.MediaPlayer]; platform-side implementation.
 *
 * @since 1.8.x
 */
@Unstable
interface PlaybackHost {
    /** Stable identifier of this display, used for log labels. */
    val uuid: UUID

    /** Viewer-selected target quality used to seed stream resolution. */
    val quality: VideoQuality

    /** Current GPU texture width (the pending one during a quality handoff). */
    val textureWidth: Int

    /** Current GPU texture height (the pending one during a quality handoff). */
    val textureHeight: Int

    /** True while the viewer has the display paused. */
    val isPaused: Boolean

    /** Effective playback mode (Watch Party overrides the display's own mode). */
    val effectiveMode: PlaybackMode

    /**
     * False when a non-looping fullscreen mode should stop at the end of the VOD instead of
     * restarting it; true everywhere else.
     */
    val shouldLoopOnEnd: Boolean

    /** Content aspect ratio of the resolved video; the player updates this on (re)resolve. */
    var videoContentAspect: Double

    /** Last fatal/recoverable media error; the player sets this to surface failures to the UI. */
    var mediaError: DreamMediaException?

    /** Invoked after a seek so the host can react (e.g. clear stale frames). */
    fun afterSeek()

    /** Begins a dual-texture quality handoff (stage a pending texture). */
    fun beginQualityHandoff()

    /** Cancels an in-flight quality handoff and drops the staged texture. */
    fun cancelQualityHandoff()

    /** Recreates the display texture at the current dimensions. */
    fun reloadTexture()

    /** Signals that non-looping VOD playback reached its end at [positionNanos]. */
    fun onPlaybackEnded(positionNanos: Long)
}
