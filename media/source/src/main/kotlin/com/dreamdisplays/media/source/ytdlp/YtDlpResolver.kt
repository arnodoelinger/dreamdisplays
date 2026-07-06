package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Subprocess-backed [MediaResolver] wrapping the [YtDlp] orchestrator. This is the slow, robust
 * fallback behind [NewPipeResolver]: a lower [priority] means [MediaResolverRegistry]
 * only reaches it after the in-process fast path has declined or failed.
 *
 * Unlike [NewPipeResolver], this path can resolve any HTTP(S) URL `yt-dlp`'s extractors understand,
 * not just YouTube. `yt-dlp`'s generic info-dict schema exposes title / uploader / thumbnail / view
 * count for every extractor (Twitch included), so those are carried on the streams and surfaced here
 * too, not just duration and live / seekable flags.
 */
object YtDlpResolver : MediaResolver {

    /** Below [NewPipeResolver] (10) so the in-process path is always tried first. */
    override val priority: Int = 0

    /** Any source with a resolvable URL is delegated to `yt-dlp`. */
    override fun canResolve(source: MediaSource): Boolean = true

    /** Pre-warms the yt-dlp format cache for [source] on a background thread. */
    override fun prefetch(source: MediaSource) {
        val url = source.toResolvableUrl() ?: return
        YtDlp.prefetchFormats(url)
    }

    /**
     * Resolves [source] by running `yt-dlp` (blocking) via [YtDlp.fetch]. Throws on subprocess
     * failure or timeout; the resolver chain catches it and either falls through or reports the error.
     */
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
            duration = durationNanos.takeIf { it > 0L }?.nanoseconds,
        )
        return ResolvedMedia(
            streams = streams.map { it.toMediaStream() },
            metadata = metadata,
            isLive = isLive,
            isSeekable = streams.any { it.isSeekable },
        )
    }
}
