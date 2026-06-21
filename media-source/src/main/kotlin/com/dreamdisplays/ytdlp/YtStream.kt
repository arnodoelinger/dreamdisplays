package com.dreamdisplays.ytdlp

import com.dreamdisplays.media.api.MediaStream
import com.dreamdisplays.media.api.MediaStreamType

/**
 * YouTube stream information, as returned by `yt-dlp`. It includes methods to check if the stream has video / audio and
 * if it's muxed (because it can be a stream or première or etc.)
 */
class YtStream(
    val url: String,
    private val mimeType: String,
    val container: String?,
    val protocol: String?,
    val resolution: String?,
    val width: Int?,
    val height: Int?,
    val audioTrackId: String?,
    val audioTrackName: String?,
    private val vcodec: String?,
    private val acodec: String?,
    val fps: Double?,
    private val tbrKbps: Double?,
    private val hasVideo: Boolean,
    private val hasAudio: Boolean,
    val isLive: Boolean,
    val isSeekable: Boolean,
    val durationNanos: Long,
) {

    /** Returns true if the stream has a video track. */
    fun hasVideo(): Boolean = hasVideo

    /** Returns true if the stream has audio, even if it's not a video stream. */
    fun hasAudio(): Boolean = hasAudio

    /** Returns true if the stream is a muxed stream (video and audio in the same file). */
    val isMuxed: Boolean get() = hasVideo && hasAudio

    /** Maps this internal stream descriptor to the media-api contract type used by [MediaResolver]. */
    fun toMediaStream(): MediaStream = MediaStream(
        url = url,
        type = when {
            hasVideo && hasAudio -> MediaStreamType.VIDEO_AUDIO
            hasVideo -> MediaStreamType.VIDEO
            else -> MediaStreamType.AUDIO
        },
        codec = vcodec?.takeIf { it.isNotBlank() && it != "none" }
            ?: acodec?.takeIf { it.isNotBlank() && it != "none" },
        width = width,
        height = height,
        fps = fps,
        bitrate = tbrKbps?.let { (it * 1000).toInt() },
        audioTrackName = audioTrackName,
        audioTrackLang = audioTrackId,
        isDefault = false,
    )

    /** Returns a human-readable string representation of the stream, including all available metadata. */
    override fun toString(): String = buildString {
        append("YtStream{").append(mimeType)
        container?.let { append(" container=").append(it) }
        protocol?.let { append(" proto=").append(it) }
        resolution?.let { append(' ').append(it) }
        vcodec?.takeIf { it != "none" }?.let { append(" v=").append(it) }
        acodec?.takeIf { it != "none" }?.let { append(" a=").append(it) }
        fps?.let { append(' ').append(it).append("fps") }
        tbrKbps?.let { append(' ').append(it).append("kbps") }
        audioTrackId?.let { append(" lang=").append(it) }
        if (isLive) append(" live")
        if (!isSeekable) append(" nonseekable")
        append('}')
    }
}
