package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.platform.YtDlpMetadataFallback

/** Metadata cache for Kick channels and VODs. */
object KickMetadataCache : PlatformMetadataCache<MediaSource.Kick, PlatformVideoMetadata>("Kick", PlatformVideoMetadata::isLive) {
    /** The cache key for [source]: `video:<uuid>` for a VOD, `channel:<slug>` for a live channel. */
    override fun cacheKey(source: MediaSource.Kick): String? = when {
        source.videoUuid != null -> "video:${source.videoUuid}"
        source.channel != null -> "channel:${source.channel}"
        else -> null
    }

    override fun fetch(source: MediaSource.Kick): PlatformVideoMetadata? =
        KickApi.metadata(source) ?: YtDlpMetadataFallback.fetch(source.url)
}
