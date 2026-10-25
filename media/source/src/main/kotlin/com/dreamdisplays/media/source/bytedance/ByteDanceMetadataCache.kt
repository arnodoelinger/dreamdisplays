package com.dreamdisplays.media.source.bytedance

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.platform.YtDlpMetadataFallback
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/**
 * Metadata cache shared by TikTok and Douyin. Their public extraction surface is already maintained
 * by `yt-dlp`, so this cache reuses the same resolved formats instead of duplicating a brittle web API.
 */
object ByteDanceMetadataCache {
    private const val TIKTOK_PREFIX = "tiktok:"
    private const val DOUYIN_PREFIX = "douyin:"

    // Failed extraction must not start another subprocess on the next preview frame.
    private val failures: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build()

    private val cache = PlatformMetadataCache(
        name = "TikTokDouyin",
        liveTtlSeconds = 45,
        staticTtlMinutes = 30,
        fetch = { key ->
            if (failures.getIfPresent(key) != null) null
            else sourceUrl(key)?.let(YtDlpMetadataFallback::fetch).also { metadata ->
                if (metadata == null) failures.put(key, true)
            }
        },
    )

    /** A namespaced cache key that keeps identical-looking TikTok and Douyin URLs distinct. */
    fun cacheKey(source: MediaSource): String? = when (source) {
        is MediaSource.TikTok -> TIKTOK_PREFIX + source.url
        is MediaSource.Douyin -> DOUYIN_PREFIX + source.url
        else -> null
    }

    /** Returns cached metadata for [source], or null when it has not been fetched yet. */
    fun get(source: MediaSource): PlatformVideoMetadata? = cacheKey(source)?.let(cache::get)

    /** Warms metadata and the shared `yt-dlp` format cache in the background. */
    fun requestAsync(source: MediaSource) {
        val key = cacheKey(source) ?: return
        if (failures.getIfPresent(key) == null) cache.requestAsync(key)
    }

    /** Fetches metadata now; intended for an existing background search coroutine. */
    fun resolveBlocking(source: MediaSource): PlatformVideoMetadata? {
        val key = cacheKey(source) ?: return null
        return cache.get(key) ?: if (failures.getIfPresent(key) == null) cache.resolveBlocking(key) else null
    }

    /** Drops cached metadata for [url] after playback asks for a fresh signed stream URL. */
    fun invalidate(url: String) {
        val key = cacheKey(MediaSource.from(url)) ?: return
        failures.invalidate(key)
        cache.invalidate(key)
    }

    /** Removes our namespace while preserving the complete original URL (including short-link tokens). */
    private fun sourceUrl(key: String): String? = when {
        key.startsWith(TIKTOK_PREFIX) -> key.removePrefix(TIKTOK_PREFIX)
        key.startsWith(DOUYIN_PREFIX) -> key.removePrefix(DOUYIN_PREFIX)
        else -> null
    }
}
