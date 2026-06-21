package com.dreamdisplays.media.source.ytdlp

import java.util.concurrent.ConcurrentHashMap

/** Cache for video titles, to avoid repeated calls to `yt-dlp`. */
object VideoTitleCache {
    private val TITLES = ConcurrentHashMap<String, String>()

    /** Stores [title] in the cache under [videoId]. */
    fun put(videoId: String, title: String) {
        if (videoId.isEmpty() || title.isEmpty()) return
        TITLES[videoId] = title
    }

    /** Returns the cached title for [videoId], or null if not yet fetched. */
    fun get(videoId: String): String? = TITLES[videoId]
}
