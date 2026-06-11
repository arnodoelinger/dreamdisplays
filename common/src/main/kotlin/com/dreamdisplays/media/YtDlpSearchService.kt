package com.dreamdisplays.media

import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.MediaSearchService
import com.dreamdisplays.ytdlp.YtDlp
import com.dreamdisplays.ytdlp.YouTubeInnerTube

/** [MediaSearchService] backed by [YtDlp] and [YouTubeInnerTube]. */
class YtDlpSearchService : MediaSearchService {
    override fun search(query: String, limit: Int): List<MediaSearchResult> = YtDlp.search(query, limit)
    override fun related(videoId: String, limit: Int): List<MediaSearchResult> = YtDlp.related(videoId, limit)
    override fun extractVideoId(url: String): String? = YtDlp.extractVideoId(url)
    override fun metadata(videoId: String): MediaSearchResult? = YouTubeInnerTube.metadata(videoId)
}
