package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * In-process Vimeo resolver: one call to the player config endpoint (see [VimeoApi]) instead of a
 * `yt-dlp` subprocess. Mirrors [com.dreamdisplays.media.source.twitch.TwitchResolver] for Twitch -
 * a fast first-party path with `yt-dlp` still behind it as the fallback when Vimeo changes shape.
 *
 * @since 1.9.0
 */
object VimeoResolver : MediaResolver {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/VimeoResolver")

    /** Above the `yt-dlp` fallback (0), alongside the other first-party in-process resolvers. */
    override val priority: Int = 10

    /** Progressive/HLS URLs are signed but stable for a while; the cache mostly absorbs prefetch->resolve. */
    private val cache: Cache<String, ResolvedMedia> = Caffeine.newBuilder()
        .maximumSize(64)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Vimeo

    override fun prefetch(source: MediaSource) {
        (source as? MediaSource.Vimeo)?.let { runCatching { resolveCached(it) } }
    }

    override fun resolve(source: MediaSource): ResolvedMedia {
        val vimeo = source as? MediaSource.Vimeo
            ?: throw UnsupportedOperationException("$source is not a Vimeo source.")
        return resolveCached(vimeo)
    }

    /** Drops [url]'s cached resolution so stall recovery re-mints signed URLs instead of re-serving them. */
    fun invalidate(url: String) {
        (MediaSource.from(url) as? MediaSource.Vimeo)?.let { cache.invalidate(VimeoMetadataCache.cacheKey(it)) }
    }

    private fun resolveCached(source: MediaSource.Vimeo): ResolvedMedia {
        val key = VimeoMetadataCache.cacheKey(source)
        cache.getIfPresent(key)?.let { return it }

        val playback = VimeoApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Vimeo video \"${source.videoId}\" is unavailable or private.")
        check(playback.streams.any { it.type.hasVideo }) { "Vimeo returned no playable video for ${source.videoId}." }

        VimeoMetadataCache.put(source, playback.metadata)
        logger.debug("Resolved Vimeo {}: {} streams.", source.videoId, playback.streams.size)
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
