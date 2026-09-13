package com.dreamdisplays.media.source.platform

import com.dreamdisplays.media.source.youtube.YtDlp
import org.slf4j.LoggerFactory

/** Metadata-only fallback when in-process API call fails: runs `yt-dlp` to fetch metadata. */
object YtDlpMetadataFallback {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Runs `yt-dlp` against [url] and reads metadata off its first stream, or null on any failure. */
    fun fetch(url: String): PlatformVideoMetadata? = runCatching { YtDlp.fetch(url) }
        .onFailure { logger.debug("yt-dlp metadata fallback failed for {}: {}.", url, it.message) }
        .getOrNull()
        ?.firstOrNull()
        ?.let { stream ->
            PlatformVideoMetadata(
                title = stream.title,
                uploader = stream.uploaderName,
                thumbnailUrl = stream.thumbnailUrl,
                viewCount = stream.viewCount,
                durationSec = stream.durationNanos.takeIf { it > 0L }?.let { it / 1_000_000_000L },
                isLive = stream.isLive,
            )
        }
}
