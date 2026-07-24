package com.dreamdisplays.media.source.platform

import com.dreamdisplays.media.source.ytdlp.YtDlp
import org.slf4j.LoggerFactory

/**
 * Metadata-only fallback for a [PlatformMetadataCache] whose fast in-process API call failed — a
 * Vimeo video with embedding restricted by its owner's privacy settings, a Kick lookup Cloudflare
 * turned away for looking like a datacenter IP, and so on. Playback already falls back to `yt-dlp`
 * in this situation ([com.dreamdisplays.media.source.ytdlp.YtDlpResolver] sits behind every
 * in-process resolver in the chain); this reuses the same subprocess purely for its title / uploader
 * / thumbnail, so a suggestion card or preview overlay isn't left blank just because the fast path
 * couldn't reach the platform's own API.
 *
 * @since 1.9.0
 */
object YtDlpMetadataFallback {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/YtDlpMetadataFallback")

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
