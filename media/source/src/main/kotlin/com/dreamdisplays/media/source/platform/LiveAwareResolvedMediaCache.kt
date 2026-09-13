package com.dreamdisplays.media.source.platform

import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import java.util.concurrent.TimeUnit

/**
 * A reusable [ResolvedMedia] cache keyed by a stable string, with a short TTL for live entries
 * (session-bound URLs) and a longer one for VOD / static entries.
 */
class LiveAwareResolvedMediaCache(
    maxSize: Long,
    private val liveTtlSeconds: Long,
    private val staticTtlMinutes: Long,
) {
    /** A cache entry that stores the [ResolvedMedia] and its TTL in nanoseconds. */
    private class Entry(val value: ResolvedMedia, val ttlNanos: Long)

    /** Caffeine cache with a custom expiry that uses the [Entry.ttlNanos] for live vs static entries. */
    private val cache: Cache<String, Entry> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfter(object : Expiry<String, Entry> {
            override fun expireAfterCreate(key: String, value: Entry, currentTime: Long) = value.ttlNanos
            override fun expireAfterUpdate(
                key: String, value: Entry, currentTime: Long, currentDuration: Long,
            ) = value.ttlNanos

            override fun expireAfterRead(
                key: String, value: Entry, currentTime: Long, currentDuration: Long,
            ) = currentDuration
        })
        .build()

    /** Returns the cached resolution for [key], or null when not yet resolved or expired. */
    fun get(key: String): ResolvedMedia? = cache.getIfPresent(key)?.value

    /** Stores [value] for [key]; TTL is chosen by [ResolvedMedia.isLive]. */
    fun put(key: String, value: ResolvedMedia) {
        val ttlNanos = if (value.isLive) TimeUnit.SECONDS.toNanos(liveTtlSeconds)
        else TimeUnit.MINUTES.toNanos(staticTtlMinutes)
        cache.put(key, Entry(value, ttlNanos))
    }

    /** Drops [key] so the next resolve re-mints the URL instead of serving a stale one. */
    fun invalidate(key: String) = cache.invalidate(key)
}
