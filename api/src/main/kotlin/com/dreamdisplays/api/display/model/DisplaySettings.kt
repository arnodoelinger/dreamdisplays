package com.dreamdisplays.api.display.model

import com.dreamdisplays.media.VideoQuality

/**
 * Represents the settings for a display.
 *
 * @since 1.0.0
 */
data class DisplaySettings(
    /** The display's volume. */
    val volume: Float = 1.0f,

    // TODO: separate sync volume as it was in old versions?
    // val syncVolume: Float = 0.5f,

    /** The display's quality. [VideoQuality.Auto] lets the client choose the best quality. */
    val quality: VideoQuality = VideoQuality.Auto,

    /** The display's brightness. */
    val brightness: Float = 1.0f,

    /** Indicates if the display is muted. */
    val muted: Boolean = false,

    /** Indicates if the display is paused. */
    val paused: Boolean = false,

    /** The display's render distance in blocks. Must be a multiple of 16 in the range [32, 192] (2–12 chunks). */
    val renderDistance: Int = 96,

    /** The URL to override the default display URL. */
    val urlOverride: String? = null,

    /** The name of the audio track to use. */
    val audioTrackName: String? = null,
) {
    init {
        require(volume in 0f..2f) { "Volume must be in [0, 2], got $volume." }
        require(brightness in 0f..2f) { "Brightness must be in [0, 2], got $brightness." }
        require(renderDistance in 32..192) { "Render distance must be in [32, 192] blocks (2–12 chunks), got $renderDistance." }
    }

    companion object {
        val DEFAULT = DisplaySettings()
    }
}
