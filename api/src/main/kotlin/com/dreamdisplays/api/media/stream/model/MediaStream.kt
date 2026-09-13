package com.dreamdisplays.api.media.stream.model

import com.dreamdisplays.api.Unstable

/**
 * One playable media track or muxed stream produced by a resolver.
 *
 * @since 1.6.x
 */
@Unstable
data class MediaStream(
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

    /** True when seeking requires decoding from start instead of seeking via demuxer. */
    val seekByDecoding: Boolean = false,
) {
    /**
     * Key that identifies this audio track across separate resolves of the same video. Prefers [audioTrackLang]
     * since it's the more stable / normalized field; many
     * providers only populate [audioTrackName] though, so that's the fallback rather than giving up.
     */
    val audioIdentity: String? get() = audioTrackLang ?: audioTrackName
}
