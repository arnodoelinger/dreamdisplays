package com.dreamdisplays.util

import com.google.gson.JsonObject

/**
 * Lenient field accessors for Gson [JsonObject]s, shared by every JSON consumer in the mod
 * (`yt-dlp` output, InnerTube responses, Modrinth update metadata). Each returns null instead of
 * throwing when the field is absent, JSON-null, or of the wrong type.
 *
 * Replaces three private near-identical copies that lived in YtDlp, YouTubeInnerTube, and UpdateCheck.
 */

/** Returns the string value of [key], or null if absent, JSON-null, or not a string. */
fun JsonObject.optString(key: String): String? {
    if (!has(key) || get(key).isJsonNull) return null
    return runCatching { get(key).asString }.getOrNull()
}

/** Returns the int value of [key], or null if absent, JSON-null, or not numeric. */
fun JsonObject.optInt(key: String): Int? {
    if (!has(key) || get(key).isJsonNull) return null
    return runCatching { get(key).asInt }.getOrNull()
}

/** Returns the double value of [key], or null if absent, JSON-null, or not numeric. */
fun JsonObject.optDouble(key: String): Double? {
    if (!has(key) || get(key).isJsonNull) return null
    return runCatching { get(key).asDouble }.getOrNull()
}

/** Returns the boolean value of [key], or [default] if absent, JSON-null, or not a boolean. */
fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean {
    if (!has(key) || get(key).isJsonNull) return default
    return runCatching { get(key).asBoolean }.getOrDefault(default)
}
