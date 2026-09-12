package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.media.source.platform.LiveAwareResolvedMediaCache
import org.slf4j.LoggerFactory

/**
 * In-process Kick resolver: one site-API call (see [KickApi]) instead of a `yt-dlp` subprocess, mirroring the other
 * first-party resolvers.
 */
object KickResolver : MediaResolverService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Alongside the other first-party in-process resolvers, above the `yt-dlp` fallback. */
    override val priority: Int = 10

    /** Live playlist URLs are session-bound, so live entries expire fast; VODs are stable. */
    private val cache = LiveAwareResolvedMediaCache(maxSize = 64, liveTtlSeconds = 25, staticTtlMinutes = 30)

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Kick

    override fun prefetch(source: MediaSource): Boolean {
        val kick = source as? MediaSource.Kick ?: return false
        return runCatching { resolveCached(kick) }.isSuccess
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val kick = source as? MediaSource.Kick
            ?: throw UnsupportedOperationException("$source is not a Kick source.")
        return resolveCached(kick)
    }

    /** Drops [url]'s cached resolution so a dying live playlist is re-minted, not re-served. */
    fun invalidate(url: String) {
        (MediaSource.from(url) as? MediaSource.Kick)?.let { source ->
            KickMetadataCache.cacheKey(source)?.let(cache::invalidate)
        }
    }

    private fun resolveCached(source: MediaSource.Kick): ResolvedMedia {
        val key = KickMetadataCache.cacheKey(source)
            ?: throw DreamMediaException.NotFound("Unrecognized Kick URL: ${source.url}.")
        cache.get(key)?.let { return it }

        val playback = KickApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Kick channel/video could not be reached.")
        KickMetadataCache.put(source, playback.metadata)

        if (playback.streams.isEmpty()) {
            // A recognized-but-offline channel: a clear message beats a decode failure downstream
            throw DreamMediaException.NotFound(
                if (source.channel != null) "This Kick channel is offline right now."
                else "This Kick video has no playable stream.",
            )
        }

        logger.debug("Resolved Kick {}: {} streams, live={}.", key, playback.streams.size, playback.metadata.isLive)
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
