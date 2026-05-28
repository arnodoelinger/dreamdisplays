package com.dreamdisplays.ytdlp

import java.util.concurrent.ConcurrentHashMap

/** Cache for video titles, to avoid repeated calls to `yt-dlp`. */
object VideoTitleCache {
    private val TITLES = ConcurrentHashMap<String, String>()

    fun put(videoId: String, title: String) {
        if (videoId.isEmpty() || title.isEmpty()) return
        TITLES[videoId] = title
    }

    fun get(videoId: String): String? = TITLES[videoId]
}
