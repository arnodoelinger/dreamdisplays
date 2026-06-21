package com.dreamdisplays.ytdlp

import com.dreamdisplays.media.api.MediaMetadata
import com.dreamdisplays.media.api.MediaResolver
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.media.api.ResolvedMedia
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Subprocess-backed [MediaResolver] wrapping the [YtDlp] orchestrator. This is the slow, robust
 * fallback behind [NewPipeResolver]: a lower [priority] means [com.dreamdisplays.media.api.MediaResolverChain]
 * only reaches it after the in-process fast path has declined or failed.
 *
 * Unlike [NewPipeResolver], this path can resolve any HTTP(S) URL `yt-dlp`'s extractors understand,
 * not just YouTube. It does not surface rich metadata (title, uploader, thumbnails) since
 * [YtDlp.fetch] only returns playable stream descriptors; only duration and live/seekable flags
 * carried on the streams are reported.
 */
object YtDlpResolver : MediaResolver {

    /** Below [NewPipeResolver] (10) so the in-process path is always tried first. */
    override val priority: Int = 0

    /** Twitch is unsupported by this pipeline; everything else is delegated to `yt-dlp`. */
    override fun canResolve(source: MediaSource): Boolean = source !is MediaSource.Twitch

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
            ?: throw UnsupportedOperationException("Twitch not supported by YtDlpResolver")
        val streams = YtDlp.fetch(url)
        check(streams.isNotEmpty()) { "yt-dlp returned no playable streams for $url" }

        val isLive = streams.any { it.isLive }
        val durationNanos = streams.firstOrNull { it.durationNanos > 0L }?.durationNanos ?: 0L
        val metadata = MediaMetadata.UNKNOWN.copy(
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
