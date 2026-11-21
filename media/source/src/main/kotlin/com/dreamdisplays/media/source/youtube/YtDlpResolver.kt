package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.media.source.youtube.model.YtStreams

/**
 * Subprocess-backed [MediaResolverService] wrapping the [YtDlp] orchestrator. This is the slow, robust
 * fallback behind [NewPipeResolver]: a lower [priority] means [MediaResolverRegistry]
 * only reaches it after the in-process fast path has declined or failed.
 */
object YtDlpResolver : MediaResolverService {
    override val priority: Int = 0

    override fun canResolve(source: MediaSource): Boolean = true

    override fun prefetch(source: MediaSource): Boolean {
        val url = source.toResolvableUrl() ?: return false
        YtDlp.prefetchFormats(url)
        return true
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("$source has no resolvable URL.")
        val streams = YtDlp.fetch(url)
        check(streams.isNotEmpty()) { "yt-dlp returned no playable streams for $url." }

        val isLive = streams.any { it.isLive }
        val durationNanos = streams.firstOrNull { it.durationNanos > 0L }?.durationNanos ?: 0L
        val first = streams.first()
        val metadata = MediaMetadata.UNKNOWN.copy(
            title = first.title,
            uploader = first.uploaderName,
            thumbnailUrl = first.thumbnailUrl,
            viewCount = first.viewCount,
            durationMs = durationNanos.takeIf { it > 0L }?.div(1_000_000L),
        )
        return ResolvedMedia(
            streams = YtStreams.preferDirect(streams).map { it.toMediaStream() },
            metadata = metadata,
            isLive = isLive,
            isSeekable = streams.any { it.isSeekable },
        )
    }
}
