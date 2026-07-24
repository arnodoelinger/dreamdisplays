package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * In-process Kick resolver: one site-API call (see [KickApi]) instead of a `yt-dlp` subprocess,
 * mirroring [com.dreamdisplays.media.source.twitch.TwitchResolver]. `yt-dlp` remains the fallback
 * for when Cloudflare turns the direct call away.
 *
 * @since 1.9.0
 */
object KickResolver : MediaResolver {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/KickResolver")

    /** Alongside the other first-party in-process resolvers, above the `yt-dlp` fallback. */
    override val priority: Int = 10

    /** Live playlist URLs are session-bound, so live entries expire fast; VODs are stable. */
    private class Entry(val value: ResolvedMedia, val ttlNanos: Long)

    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .maximumSize(64)
        .expireAfter(object : Expiry<String, Entry> {
            override fun expireAfterCreate(key: String, value: Entry, currentTime: Long) = value.ttlNanos
            override fun expireAfterUpdate(key: String, value: Entry, currentTime: Long, currentDuration: Long) =
                value.ttlNanos

            override fun expireAfterRead(key: String, value: Entry, currentTime: Long, currentDuration: Long) =
                currentDuration
        })
        .build()

    private val LIVE_TTL_NANOS = TimeUnit.SECONDS.toNanos(25)
    private val VOD_TTL_NANOS = TimeUnit.MINUTES.toNanos(30)

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Kick

    override fun prefetch(source: MediaSource) {
        (source as? MediaSource.Kick)?.let { runCatching { resolveCached(it) } }
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
        cache.getIfPresent(key)?.let { return it.value }

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
        cache.put(key, Entry(resolved, if (playback.metadata.isLive) LIVE_TTL_NANOS else VOD_TTL_NANOS))
        return resolved
    }
}
