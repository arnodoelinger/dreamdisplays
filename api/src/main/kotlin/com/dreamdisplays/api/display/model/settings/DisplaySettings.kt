package com.dreamdisplays.api.display.model.settings

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.model.VideoQuality

/**
 * Represents the settings for a display.
 *
 * @since 1.0.x
 */
@Unstable
data class DisplaySettings(
    /** The display's volume. */
    val volume: Float = 1.0f,

    /** The display's quality. [VideoQuality.Auto] lets the client choose the best quality. */
    val quality: VideoQuality = VideoQuality.Auto,

    /** The display's brightness. */
    val brightness: Float = 1.0f,

    /** Indicates if the display is muted. */
    val muted: Boolean = false,

    /** Indicates if the display is paused. */
    val paused: Boolean = false,

    /** The URL to override the default display URL. */
    val urlOverride: String? = null,

    /** The name of the audio track to use. */
    val audioTrackName: String? = null,
) {
    init {
        require(volume in 0f..2f) { "Volume must be in [0, 2], got $volume." }
        require(brightness in 0f..2f) { "Brightness must be in [0, 2], got $brightness." }
    }

    companion object {
        val DEFAULT = DisplaySettings()
    }
}
