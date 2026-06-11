package com.dreamdisplays.media.api

/**
 * Requested video quality for a display: either [Auto] (let the client pick the best available
 * stream) or a [Fixed] target height in pixels.
 *
 * Persisted and displayed via [serialize] / [parse] as a plain label ("auto" or a bare height such
 * as "720"), so on-disk and wire formats stay string-compatible with earlier versions.
 *
 * @since 1.8.0
 */
sealed interface VideoQuality {
    /** Quality is chosen automatically from the available streams. */
    data object Auto : VideoQuality

    /** A specific target height in pixels (e.g. 720, 1080). [height] is always positive. */
    data class Fixed(val height: Int) : VideoQuality

    /** Target height in pixels, or null for [Auto]. */
    val targetHeight: Int? get() = (this as? Fixed)?.height

    /** Serializes to the persisted/label form: "auto" for [Auto], or the bare height for [Fixed]. */
    fun serialize(): String = when (this) {
        Auto -> AUTO_LABEL
        is Fixed -> height.toString()
    }

    companion object {
        /** Label used for [Auto] in config, persistence, and the public API. */
        const val AUTO_LABEL = "auto"

        /** Client default when nothing is persisted. */
        val DEFAULT: VideoQuality = Fixed(720)

        /**
         * Parses a raw label into a [VideoQuality]. Null, blank, or "auto" (case-insensitive) yield
         * [Auto]; a leading positive integer (tolerating a trailing "p", e.g. "720p") yields [Fixed];
         * anything else falls back to [Auto].
         */
        fun parse(raw: String?): VideoQuality {
            if (raw == null) return Auto
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals(AUTO_LABEL, ignoreCase = true)) return Auto
            val start = trimmed.indexOfFirst { it.isDigit() }
            if (start < 0) return Auto
            val digits = trimmed.substring(start).takeWhile { it.isDigit() }
            val height = digits.toIntOrNull()
            return if (height != null && height > 0) Fixed(height) else Auto
        }
    }
}
