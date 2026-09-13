package com.dreamdisplays.api.media.model

import com.dreamdisplays.api.Unstable

/**
 * Requested video quality: [Auto] (client picks) or [Fixed] height; persisted as label.
 *
 * @since 1.8.x
 */
@Unstable
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
        val DEFAULT: VideoQuality = Fixed(1080)

        /** Parses a label into [VideoQuality]: null / "auto" -> Auto, digits -> Fixed, else Auto. */
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
