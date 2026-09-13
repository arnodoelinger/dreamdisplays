package com.dreamdisplays.api.display.model.settings

import com.dreamdisplays.api.Unstable
import kotlinx.serialization.Serializable

/**
 * Client-local preferences: volume, quality, mute, URL / language overrides, PiP position, acoustics.
 *
 * @since 1.0.x
 */
@Unstable
@Serializable
data class ClientDisplaySettings(
    /** Volume in the range [0.0, 1.0]. */
    var volume: Float = DEFAULT_VOLUME,

    /** Video quality, e.g. "720" or "1080". */
    var quality: String = "1080",

    /** Brightness in the range [0.0, 2.0]. */
    var brightness: Float = 1.0f,

    /** Whether the display is muted. */
    var muted: Boolean = false,

    /** Whether the display is paused. */
    var paused: Boolean = true,

    /** URL override for the video, or null if not overridden. */
    var urlOverride: String? = null,

    /** Language override for the video, or null if not overridden. */
    var langOverride: String? = null,

    /** Viewer-picked audio track language (from the audio-track dropdown), re-applied after rejoining. */
    var audioTrackLang: String? = null,

    /** Whether the viewer turned subtitles on for this display. */
    var subtitlesEnabled: Boolean = false,

    /** Viewer-picked subtitle track language, re-applied after rejoining or when the video changes. */
    var subtitleTrackLang: String? = null,

    /** Last known playback position in nanoseconds, resumed on Local displays after a restart. */
    var savedTimeNanos: Long = 0,

    /** Whether the viewer pinned this display to a Picture-in-Picture overlay; re-opened on rejoin regardless of render distance. */
    var pipOpen: Boolean = false,

    /** Picture-in-Picture anchor point. */
    var pipAnchor: String? = null,

    /** Height of the PiP as a fraction of the screen, or `0` when the viewer never resized it. */
    var pipSizeFraction: Float = 0f,

    /** Whether the 3D acoustics engine applies to this display; false forces the legacy distance-gain-only path. */
    var acousticsEnabled: Boolean = true,
) {

    companion object {
        /** Default volume for all displays. The UI presents this as 50% (slider range is [0, 1] -> [0%, 200%]). */
        const val DEFAULT_VOLUME = 0.25f
    }
}
