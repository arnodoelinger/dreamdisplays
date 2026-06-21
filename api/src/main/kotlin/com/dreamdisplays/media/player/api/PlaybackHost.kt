package com.dreamdisplays.media.player.api

import com.dreamdisplays.media.DreamMediaException
import com.dreamdisplays.media.VideoQuality
import com.dreamdisplays.api.playback.PlaybackMode
import java.util.UUID

/**
 * The display a [com.dreamdisplays.media.player.MediaPlayer] drives, abstracted away from the Minecraft
 * `DisplayScreen`. The platform layer implements this; the player reads playback-relevant state and
 * signals render-side transitions through it.
 */
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
