@file:OptIn(DreamDisplaysUnstableApi::class)

package com.dreamdisplays.media.api

import com.dreamdisplays.api.DreamDisplaysUnstableApi

data class ResolvedMedia(
    val streams: List<MediaStream>,
    val metadata: MediaMetadata,
    val isLive: Boolean,
    val isSeekable: Boolean,
) {
    val videoStreams: List<MediaStream> get() = streams.filter { it.type.hasVideo }
    val audioStreams: List<MediaStream> get() = streams.filter { it.type.hasAudio }
    val hasVideo: Boolean get() = videoStreams.isNotEmpty()
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()
}
