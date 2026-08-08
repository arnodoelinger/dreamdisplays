package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlinx.serialization.Serializable

/**
 * Client-local preferences.
 *
 * These are the viewer's own choices (volume, quality, mute, an optional URL / language override) and are
 * kept separate from the server-authoritative display snapshot.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
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

    /** Last known playback position in nanoseconds, resumed on Local displays after a restart. */
    var savedTimeNanos: Long = 0,

    /** Viewer-chosen render distance in blocks, or `0` if never customized (falls back to the config default). */
    var renderDistance: Int = 0,

    /** Whether the viewer pinned this display to a Picture-in-Picture overlay; re-opened on rejoin regardless of render distance. */
    var pipOpen: Boolean = false,

    /**
     * Name of the [com.dreamdisplays.platform.client.ui.PipAnchor] the viewer last left this
     * display's PiP at, or null to use the caller's default. Stored as a name rather than an ordinal
     * so reordering the anchor enum can't silently move everyone's overlay.
     */
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
