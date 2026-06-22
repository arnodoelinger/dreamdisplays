package com.dreamdisplays.api.media.stream

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** One playable media track or muxed stream produced by a resolver. */
@DreamDisplaysUnstableApi data class MediaStream(
    /** Direct URL the player can open. */
    val url: String,

    /** Whether this stream contains video, audio, or both. */
    val type: MediaStreamType,

    /** Codec name, if the resolver exposed it. */
    val codec: String?,

    /** Video width in pixels, or null for audio-only / unknown streams. */
    val width: Int?,

    /** Video height in pixels, or null for audio-only / unknown streams. */
    val height: Int?,

    /** Video frame rate, or null for audio-only / unknown streams. */
    val fps: Double?,

    /** Stream bitrate in bits per second, if known. */
    val bitrate: Int?,

    /** Human-readable audio track name, if this stream carries audio. */
    val audioTrackName: String?,

    /** Audio language code, if this stream carries audio. */
    val audioTrackLang: String?,

    /** True when the provider marks this stream as the default track. */
    val isDefault: Boolean = false,
) {
    /** Compact quality label for UI display, preferring video height over bitrate. */
    val qualityLabel: String get() = when {
        height != null && fps != null && fps > 50 -> "${height}p${fps.toInt()}"
        height != null -> "${height}p"
        bitrate != null -> "${bitrate / 1000}kbps"
        else -> "unknown"
    }
}
