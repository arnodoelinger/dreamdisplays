package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.service.keys.MediaServices
import com.dreamdisplays.api.media.search.model.MediaChapter
import com.dreamdisplays.media.source.youtube.cache.YouTubeChapterCache
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayScreen

/**
 * Chapter markers for a display's current video. YouTube-only by construction: every other source
 * yields no video id here, and so reports no chapters.
 */
object DisplayChapters {
    /** Chapters for [ds]'s current video, requesting them on first ask and empty until they arrive. */
    fun of(ds: DisplayScreen): List<MediaChapter> {
        val videoId = DreamServices.registry.getOrNull(MediaServices.SEARCH)?.extractVideoId(ds.videoUrl ?: "")
            ?: return emptyList()
        val cached = YouTubeChapterCache.get(videoId)
        if (cached == null) {
            YouTubeChapterCache.requestAsync(videoId)
            return emptyList()
        }
        return cached
    }

    /** Title of the chapter [ds] is playing right now, or null when the video has no chapters. */
    fun activeTitle(ds: DisplayScreen): String? {
        val nanos = ds.currentTimeNanos
        return of(ds).lastOrNull { it.startSeconds * 1_000_000_000L <= nanos }?.title
    }
}
