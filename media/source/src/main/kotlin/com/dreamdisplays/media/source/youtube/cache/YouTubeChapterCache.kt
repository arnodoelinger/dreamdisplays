package com.dreamdisplays.media.source.youtube.cache

import com.dreamdisplays.api.media.search.model.MediaChapter
import com.dreamdisplays.media.source.youtube.YouTubeInnerTube
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * In-memory cache of YouTube chapter markers keyed by video id. Deliberately separate from
 * [VideoMetadataCache]: that one is also filled with search-result stubs, which carry no chapters
 * and would then block any fetch that could supply them.
 */
object YouTubeChapterCache {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Time-to-live for cached chapter lists. */
    private const val CACHE_TTL_MINUTES = 30L

    /** Time-to-live for in-flight chapter fetches. */
    private const val IN_FLIGHT_TTL_MINUTES = 2L

    /** Maximum number of entries in the cache. */
    private const val MAX_ENTRIES = 200L

    /** In-memory cache of chapter lists keyed by video id. */
    private val CACHE: Cache<String, List<MediaChapter>> = Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES)
        .expireAfterAccess(CACHE_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    /** Tracks video ids for which a chapter fetch is currently in flight. */
    private val IN_FLIGHT: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES)
        .expireAfterWrite(IN_FLIGHT_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    /** Chapters for [videoId], or null while they have not been fetched yet. */
    fun get(videoId: String): List<MediaChapter>? = CACHE.getIfPresent(videoId)

    /** Fetches chapters for [videoId] in the background unless they are cached or already in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        if (CACHE.getIfPresent(videoId) != null) return
        if (IN_FLIGHT.asMap().putIfAbsent(videoId, true) != null) return
        DreamCoroutines.clientIo.launch { fetchAndStore(videoId) }
    }

    /** Fetches chapters for [videoId] via [YouTubeInnerTube] and caches them, empty list included. */
    private fun fetchAndStore(videoId: String) {
        runCatching { YouTubeInnerTube.chapters(videoId) }
            .onSuccess { CACHE.put(videoId, it) }
            .onFailure { e -> logger.warn("Chapter fetch failed for $videoId: ${e.message}") }
            .also { IN_FLIGHT.invalidate(videoId) }
    }
}
