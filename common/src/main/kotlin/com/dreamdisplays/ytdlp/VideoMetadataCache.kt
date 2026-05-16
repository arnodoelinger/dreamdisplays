package com.dreamdisplays.ytdlp

import me.inotsleep.utils.logging.LoggingManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Video metadata cache that stores `YtVideoInfo` objects in memory, keyed by video ID. This is used to avoid repeated
 * calls to `yt-dlp`
 */
object VideoMetadataCache {

    private val CACHE = ConcurrentHashMap<String, YtVideoInfo>()
    private val IN_FLIGHT = ConcurrentHashMap<String, Boolean>()
    private val EXEC = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DD-VideoMeta").apply { isDaemon = true }
    }


    fun put(videoId: String, info: YtVideoInfo) {
        if (videoId.isEmpty()) return
        CACHE[videoId] = info
        VideoTitleCache.put(videoId, info.title)
    }


    fun get(videoId: String): YtVideoInfo? = CACHE[videoId]


    fun requestAsync(videoId: String) {
        if (videoId.isEmpty()) return
        if (CACHE.containsKey(videoId)) return
        if (IN_FLIGHT.putIfAbsent(videoId, true) != null) return
        EXEC.submit { fetchAndStore(videoId) }
    }

    private fun fetchAndStore(videoId: String) {
        try {
            YouTubeInnerTube.metadata(videoId)?.let { put(videoId, it) }
        } catch (e: Exception) {
            LoggingManager.warn("Metadata fetch failed for $videoId: ${e.message}")
        } finally {
            IN_FLIGHT.remove(videoId)
        }
    }
}
