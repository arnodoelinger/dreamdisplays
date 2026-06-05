package com.dreamdisplays.ytdlp

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Video metadata cache that stores `YtVideoInfo` objects in memory, keyed by video ID. This is used to avoid repeated
 * calls to `yt-dlp`
 */
object VideoMetadataCache {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoMetadataCache")
    private val CACHE = ConcurrentHashMap<String, YtVideoInfo>()
    private val IN_FLIGHT = ConcurrentHashMap<String, Boolean>()
    private val EXEC = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DD-VideoMeta").apply { isDaemon = true }
    }

    /** Stores [info] in the cache under [videoId] and also updates [VideoTitleCache]. */
    fun put(videoId: String, info: YtVideoInfo) {
        if (videoId.isEmpty()) return
        CACHE[videoId] = info
        VideoTitleCache.put(videoId, info.title)
    }

    /** Returns the cached [YtVideoInfo] for [videoId], or null if not yet fetched. */
    fun get(videoId: String): YtVideoInfo? = CACHE[videoId]

    /** Fetches and caches metadata for [videoId] in the background if it is not already cached or in flight. */
    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        if (CACHE.containsKey(videoId)) return
        if (IN_FLIGHT.putIfAbsent(videoId, true) != null) return
        EXEC.submit { fetchAndStore(videoId) }
    }

    /** Calls [YouTubeInnerTube.metadata] for [videoId] and stores the result; logs a warning on failure. */
    private fun fetchAndStore(videoId: String) {
        try {
            YouTubeInnerTube.metadata(videoId)?.let { put(videoId, it) }
        } catch (e: Exception) {
            logger.warn("Metadata fetch failed for $videoId: ${e.message}")
        } finally {
            IN_FLIGHT.remove(videoId)
        }
    }
}
