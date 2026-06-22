package com.dreamdisplays.server.utils

import org.semver4j.Semver

/**
 * Parses client-supplied mod / plugin version strings into a [Semver], bounded against hostile
 * input. The version comes straight off the network with no length cap from the decoder, so this
 * enforces one itself before any regex work runs.
 */
object VersionUtil {
    /** Comfortably above any real version string; caps the work a single malformed packet can trigger. */
    private const val MAX_VERSION_LENGTH = 64
    private val DISALLOWED_CHARS = "[^0-9A-Za-z+.-]".toRegex()

    /** Returns the parsed [Semver], or null if [raw] is empty, oversized, or not coercible to a version. */
    fun parseOrNull(raw: String): Semver? {
        if (raw.isEmpty() || raw.length > MAX_VERSION_LENGTH) return null
        val sanitized = raw.trim().replace(DISALLOWED_CHARS, "").takeIf { it.isNotEmpty() } ?: return null
        return Semver.coerce(sanitized)
    }
}
