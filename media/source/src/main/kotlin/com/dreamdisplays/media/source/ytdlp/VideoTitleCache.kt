package com.dreamdisplays.media.source.ytdlp

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/** Cache for video titles, to avoid repeated calls to `yt-dlp`. */
object VideoTitleCache {
    private val TITLES: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build()

    /** Stores [title] in the cache under [videoId]. */
    fun put(videoId: String, title: String) {
        if (videoId.isEmpty() || title.isEmpty()) return
        TITLES.put(videoId, title)
    }

    /** Returns the cached title for [videoId], or null if not yet fetched. */
    fun get(videoId: String): String? = TITLES.getIfPresent(videoId)
}
