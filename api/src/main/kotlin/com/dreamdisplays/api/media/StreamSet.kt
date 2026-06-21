package com.dreamdisplays.api.media


data class StreamSet(
    val videoStream: MediaStream?,
    val audioStream: MediaStream?,
    val allStreams: List<MediaStream>,
) {
    val isMuxed: Boolean get() = videoStream?.type == MediaStreamType.VIDEO_AUDIO
    val hasVideo: Boolean get() = videoStream != null
    val hasAudio: Boolean get() = audioStream != null
}
