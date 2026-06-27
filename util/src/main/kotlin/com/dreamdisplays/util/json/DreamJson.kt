package com.dreamdisplays.util.json

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for Dream Displays' own schemas and tolerant third-party extractors.
 */
object DreamJson {
    /** Compact, tolerant JSON for network payloads and cache files. */
    val compact: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Pretty-printed JSON for human-editable local config/state files. */
    val pretty: Json = Json(compact) {
        prettyPrint = true
    }
}
