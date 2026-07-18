package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** In-memory cache of [MediaSearchResult] keyed by YouTube video ID. */
object VideoMetadataCache {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoMetadataCache")
    private const val CACHE_TTL_MINUTES = 30L
    private const val IN_FLIGHT_TTL_MINUTES = 2L

    private val CACHE: Cache<String, MediaSearchResult> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterAccess(CACHE_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(IN_FLIGHT_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    /** Stores [info] in the cache under [videoId] and also updates [VideoTitleCache]. */
    fun put(videoId: String, info: MediaSearchResult) {
        if (videoId.isEmpty()) return
        CACHE.put(videoId, info)
        VideoTitleCache.put(videoId, info.title)
    }

    /** Returns the cached [MediaSearchResult] for [videoId], or null if not yet fetched. */
    fun get(videoId: String): MediaSearchResult? = CACHE.getIfPresent(videoId)

    /** Fetches and caches metadata for [videoId] in the background if it is not already cached or in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        if (CACHE.getIfPresent(videoId) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(videoId, true) != null) return
        DreamCoroutines.clientIo.launch { fetchAndStore(videoId) }
    }

    /** Fetches metadata for [videoId] via [YouTubeInnerTube] and stores the result. */
    private fun fetchAndStore(videoId: String) {
        runCatching { YouTubeInnerTube.metadata(videoId) }
            .onSuccess { metadata -> metadata?.let { put(videoId, it) } }
            .onFailure { e -> logger.warn("Metadata fetch failed for $videoId: ${e.message}") }
            .also { IN_FLIGHT.invalidate(videoId) }
    }
}
