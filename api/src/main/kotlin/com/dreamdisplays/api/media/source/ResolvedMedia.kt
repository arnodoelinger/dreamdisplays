package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.media.stream.MediaStream

/** Fully resolved media: candidate streams plus metadata and timeline capabilities. */
@DreamDisplaysUnstableApi
data class ResolvedMedia(
    /** All playable streams returned by the resolver. */
    val streams: List<MediaStream>,

    /** Best metadata known for the resolved source. */
    val metadata: MediaMetadata,

    /** True for live streams where duration may be unknown and seeking may be restricted. */
    val isLive: Boolean,

    /** True when playback may seek within the media timeline. */
    val isSeekable: Boolean,
) {
    /** Streams that contain video. */
    val videoStreams: List<MediaStream> get() = streams.filter { it.type.hasVideo }

    /** Streams that contain audio. */
    val audioStreams: List<MediaStream> get() = streams.filter { it.type.hasAudio }

    /** True when any stream contains video. */
    val hasVideo: Boolean get() = videoStreams.isNotEmpty()

    /** True when any stream contains audio. */
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()
}
