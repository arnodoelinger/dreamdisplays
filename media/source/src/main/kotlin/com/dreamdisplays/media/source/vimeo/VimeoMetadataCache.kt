package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.platform.YtDlpMetadataFallback

/** Metadata cache for Vimeo videos, so the menu can show a real title / uploader / thumbnail for a pasted Vimeo link. */
object VimeoMetadataCache : PlatformMetadataCache<MediaSource.Vimeo, PlatformVideoMetadata>("Vimeo", PlatformVideoMetadata::isLive) {
    /** The cache key for [source]: `<videoId>` or `<videoId>/<hash>` for an unlisted video. */
    override fun cacheKey(source: MediaSource.Vimeo): String =
        if (source.hash != null) "${source.videoId}/${source.hash}" else source.videoId

    override fun fetch(source: MediaSource.Vimeo): PlatformVideoMetadata? =
        VimeoApi.metadata(source) ?: YtDlpMetadataFallback.fetch(source.url)
}
