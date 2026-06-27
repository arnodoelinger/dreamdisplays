package com.dreamdisplays.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lenient field accessors for `kotlinx.serialization` [JsonObject]s. Each helper returns null instead
 * of throwing when the field is absent, JSON-null, or of the wrong type.
 */

/** Returns [this] as a JSON object, or null when it has another shape. */
fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

/** Returns [this] as a JSON array, or null when it has another shape. */
fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

/** Returns the object value of [key], or null when missing / wrong-shaped. */
fun JsonObject.obj(key: String): JsonObject? = this[key].asJsonObjectOrNull()

/** Returns the array value of [key], or null when missing / wrong-shaped. */
fun JsonObject.array(key: String): JsonArray? = this[key].asJsonArrayOrNull()

/** Returns the string value of [key], or null if absent, JSON-null, or not a primitive. */
fun JsonObject.optString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.content }.getOrNull()
}

/** Returns the int value of [key], or null if absent, JSON-null, or not numeric. */
fun JsonObject.optInt(key: String): Int? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.intOrNull }.getOrNull()
}

/** Returns the double value of [key], or null if absent, JSON-null, or not numeric. */
fun JsonObject.optDouble(key: String): Double? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.doubleOrNull }.getOrNull()
}

/** Returns the boolean value of [key], or [default] if absent, JSON-null, or not a boolean. */
fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean {
    val value = this[key] ?: return default
    if (value is JsonNull) return default
    return runCatching { value.jsonPrimitive.booleanOrNull }.getOrNull() ?: default
}
