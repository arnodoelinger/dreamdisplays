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
    var quality: String = "720",

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
) {

    companion object {
        /** Default volume for local displays. The UI presents this as 100%. */
        const val DEFAULT_VOLUME = 0.5f

        /** Default volume for synced and broadcast displays. The UI presents this as 50%. */
        const val DEFAULT_SHARED_MODE_VOLUME = 0.25f
    }
}
