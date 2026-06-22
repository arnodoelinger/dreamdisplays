package com.dreamdisplays.platform.client.storage

/**
 * Per-display, client-local preferences persisted in `client-display-settings.json`.
 *
 * These are the viewer's own choices (volume, quality, mute, an optional URL/language override) and are
 * kept separate from the server-authoritative display snapshot.
 */
class ClientDisplaySettings {
    /** Volume in the range [0.0, 1.0]. */
    var volume: Float = 0.5f

    /** Video quality, e.g. "720" or "1080". */
    var quality: String = "720"

    /** Brightness in the range [0.0, 2.0]. */
    var brightness: Float = 1.0f

    /** Whether the display is muted. */
    var muted: Boolean = false

    /** Whether the display is paused. */
    var paused: Boolean = true

    /** URL override for the video, or null if not overridden. */
    var urlOverride: String? = null

    /** Language override for the video, or null if not overridden. */
    var langOverride: String? = null
}
