package com.dreamdisplays.displays.store

/**
 * Per-display, client-local preferences persisted in `client-display-settings.json`.
 *
 * These are the viewer's own choices (volume, quality, mute, an optional URL/language override) and are
 * kept separate from the server-authoritative [FullDisplayData].
 */
class ClientDisplaySettings {
    var volume: Float = 0.5f
    var quality: String = "720"
    var brightness: Float = 1.0f
    var muted: Boolean = false
    var paused: Boolean = true
    var urlOverride: String? = null
    var langOverride: String? = null
}
