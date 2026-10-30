package com.dreamdisplays.media.source.platform

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.runtime.cache.MediaCache

/**
 * Metadata cache for one platform's [S] sources, so a pasted link shows a real title / thumbnail / live badge without
 * waiting for stream resolution. Subclasses only say how a source is keyed and fetched.
 */
abstract class PlatformMetadataCache<S : MediaSource, M : Any>(name: String, isLive: (M) -> Boolean) {
    private val cache = MediaCache<String, M>(name, maxSize = 500, MediaCache.liveAware(isLive))

    /** The stable cache key for [source], so different kinds of source (a VOD vs. a live channel) never collide. */
    abstract fun cacheKey(source: S): String?

    /** Fetches fresh metadata for [source] (blocking), or null when there is none. */
    protected abstract fun fetch(source: S): M?

    /** Returns cached metadata for [key], or null when not yet fetched. */
    fun get(key: String?): M? = key?.let(cache::get)

    /** Seeds [metadata] for [source]; used by resolvers that fetch it during playback anyway. */
    fun put(source: S, metadata: M) {
        cacheKey(source)?.let { cache.put(it, metadata) }
    }

    /** Warms the cache for [source] in the background (client-thread safe). */
    fun requestAsync(source: S) {
        cacheKey(source)?.let { key -> cache.requestAsync(key) { fetch(source) } }
    }

    /** @return cached metadata for [source], else fetches it now (blocking); for background search threads. */
    fun resolveBlocking(source: S): M? = cacheKey(source)?.let { key -> cache.getOrFetch(key) { fetch(source) } }
}
