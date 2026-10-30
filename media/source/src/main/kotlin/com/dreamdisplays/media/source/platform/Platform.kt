package com.dreamdisplays.media.source.platform

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.media.runtime.cache.MediaCache
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a platform answers for one source. */
data class PlatformPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val isSeekable: Boolean,
)

/**
 * A first-party streaming platform (Twitch, Kick, Vimeo, Bilibili, ...) resolved in-process, without `yt-dlp`.
 *
 * A new platform implements [resolveUncached] plus a [PlatformMetadataCache] and joins [Platforms.all].
 */
abstract class Platform<S : MediaSource, M : Any>(
    val name: String,
    private val sourceType: Class<S>,
    val metadata: PlatformMetadataCache<S, M>,
    maxCached: Long = 64,
    staticTtl: Duration = 30.minutes,
) : MediaResolverService {
    protected val logger = LoggerFactory.getLogger(javaClass)

    override val priority: Int = 10

    // Live URLs carry short-lived session tokens, so they are re-minted far sooner than a VOD's
    private val cache = MediaCache<String, ResolvedMedia>(name, maxCached, MediaCache.liveAware(ResolvedMedia::isLive, live = 25.seconds, static = staticTtl))

    /** Resolves [source] from the platform itself (blocking); throws a [DreamMediaException] when nothing plays. */
    protected abstract fun resolveUncached(source: S): ResolvedMedia

    override fun canResolve(source: MediaSource): Boolean = sourceType.isInstance(source)

    override fun prefetch(source: MediaSource): Boolean = canResolve(source) && runCatching { resolve(source) }.isSuccess

    override fun resolve(source: MediaSource): ResolvedMedia {
        if (!canResolve(source)) throw UnsupportedOperationException("$source is not a $name source.")
        val typed = sourceType.cast(source)
        val key = metadata.cacheKey(typed) ?: throw DreamMediaException.NotFound("Unrecognized $name URL: $source.")
        return cache.get(key) ?: resolveUncached(typed).also { cache.put(key, it) }
    }

    /** Drops [url]'s cached resolution so stall recovery re-mints a dead playlist instead of re-serving it. */
    fun invalidate(url: String) {
        val source = MediaSource.from(url).takeIf(::canResolve)?.let(sourceType::cast) ?: return
        metadata.cacheKey(source)?.let(cache::invalidate)
    }

    /**
     * Seeds [cache] from this playback and turns it into [ResolvedMedia],
     * or throws [offlineMessage] when it has no streams.
     */
    protected fun PlatformPlayback.toResolvedMedia(
        source: S, offlineMessage: String, cache: PlatformMetadataCache<S, PlatformVideoMetadata>,
    ): ResolvedMedia {
        cache.put(source, this.metadata)
        if (streams.isEmpty()) throw DreamMediaException.NotFound(offlineMessage)
        logger.debug("Resolved {} {}: {} streams, live={}.", name, source, streams.size, this.metadata.isLive)
        return ResolvedMedia(
            streams = streams,
            metadata = this.metadata.toMediaMetadata(),
            isLive = this.metadata.isLive,
            isSeekable = isSeekable,
        )
    }
}
