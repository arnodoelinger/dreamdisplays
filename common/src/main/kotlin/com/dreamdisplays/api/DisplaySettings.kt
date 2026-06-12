@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.api

import com.dreamdisplays.media.api.VideoQuality

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

    /** The display's render distance. You should not change this value because of performance reasons. */
    val renderDistance: Int = 32,

    /** Indicates if synchronization is enabled. */
    val syncEnabled: Boolean = true,

    /** The URL to override the default display URL. */
    val urlOverride: String? = null,

    /** The name of the audio track to use. */
    val audioTrackName: String? = null,
) {
    init {
        require(volume in 0f..2f) { "Volume must be in [0, 2], got $volume" }
        require(brightness in 0f..2f) { "Brightness must be in [0, 2], got $brightness" }
        require(renderDistance > 0) { "Render distance must be positive" }
    }

    companion object {
        val DEFAULT = DisplaySettings()
    }
}
