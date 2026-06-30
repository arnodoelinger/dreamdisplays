package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.media.source.ytdlp.YtDlp
import com.dreamdisplays.media.source.ytdlp.YouTubeInnerTube

/** [MediaSearchService] backed by [YtDlp] and [YouTubeInnerTube]. */
class YtDlpSearchService : MediaSearchService {
    override fun search(query: String, limit: Int): List<MediaSearchResult> = YtDlp.search(query, limit)
    override fun related(videoId: String, limit: Int): List<MediaSearchResult> = YtDlp.related(videoId, limit)
    override fun extractVideoId(url: String): String? = YtDlp.extractVideoId(url)
    override fun metadata(videoId: String): MediaSearchResult? = YouTubeInnerTube.metadata(videoId)
}
