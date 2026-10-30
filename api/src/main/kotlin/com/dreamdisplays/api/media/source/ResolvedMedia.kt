package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.SubtitleTrack

/**
 * Fully resolved media: candidate streams plus metadata and timeline capabilities.
 *
 * @since 1.8.x
 */
@Experimental
data class ResolvedMedia(
    /** All playable streams returned by the resolver. */
    val streams: List<MediaStream>,

    /** Best metadata known for the resolved source. */
    val metadata: MediaMetadata,

    /** True for live streams where duration may be unknown and seeking may be restricted. */
    val isLive: Boolean,

    /** True when playback may seek within the media timeline. */
    val isSeekable: Boolean,

    /** Selectable subtitle / caption tracks, if the resolver exposed any. Empty for most sources. */
    val subtitles: List<SubtitleTrack> = [],
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
