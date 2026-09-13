package com.dreamdisplays.media.source.bilibili

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.media.source.platform.LiveAwareResolvedMediaCache
import org.slf4j.LoggerFactory

/**
 * In-process Bilibili resolver: one site-API call (see [BilibiliApi]) instead of a `yt-dlp` subprocess, mirroring the
 * other first-party resolvers.
 */
object BilibiliResolver : MediaResolverService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Alongside the other first-party in-process resolvers, above the `yt-dlp` fallback. */
    override val priority: Int = 10

    /** Live playurl URLs are session-bound, so live entries expire fast; VODs are stable. */
    private val cache = LiveAwareResolvedMediaCache(maxSize = 64, liveTtlSeconds = 25, staticTtlMinutes = 30)

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Bilibili

    override fun prefetch(source: MediaSource): Boolean {
        val bilibili = source as? MediaSource.Bilibili ?: return false
        return runCatching { resolveCached(bilibili) }.isSuccess
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val bilibili = source as? MediaSource.Bilibili
            ?: throw UnsupportedOperationException("$source is not a Bilibili source.")
        return resolveCached(bilibili)
    }

    /** Drops [url]'s cached resolution so a dying live playurl is re-minted, not re-served. */
    fun invalidate(url: String) {
        (MediaSource.from(url) as? MediaSource.Bilibili)?.let { source ->
            BilibiliMetadataCache.cacheKey(source)?.let(cache::invalidate)
        }
    }

    private fun resolveCached(source: MediaSource.Bilibili): ResolvedMedia {
        val key = BilibiliMetadataCache.cacheKey(source)
            ?: throw DreamMediaException.NotFound("Unrecognized Bilibili URL: ${source.url}.")
        cache.get(key)?.let { return it }

        val playback = BilibiliApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Bilibili video/room could not be reached.")
        BilibiliMetadataCache.put(source, playback.metadata)

        if (playback.streams.isEmpty()) {
            throw DreamMediaException.NotFound(
                if (source.roomId != null) "This Bilibili live room is offline right now."
                else "This Bilibili video is unavailable or private.",
            )
        }

        logger.debug(
            "Resolved Bilibili {}: {} streams, live={}.", key, playback.streams.size, playback.metadata.isLive,
        )
        val resolved = ResolvedMedia(
            streams = playback.streams,
            metadata = playback.metadata.toMediaMetadata(),
            isLive = playback.metadata.isLive,
            isSeekable = playback.isSeekable,
        )
        cache.put(key, resolved)
        return resolved
    }
}
