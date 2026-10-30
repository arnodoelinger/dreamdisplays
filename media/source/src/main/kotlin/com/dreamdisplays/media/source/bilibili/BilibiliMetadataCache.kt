package com.dreamdisplays.media.source.bilibili

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.platform.YtDlpMetadataFallback

/** Metadata cache for Bilibili VODs and live rooms, keyed so a VOD, its parts, and a live room never collide. */
object BilibiliMetadataCache :
    PlatformMetadataCache<MediaSource.Bilibili, PlatformVideoMetadata>("Bilibili", PlatformVideoMetadata::isLive) {
    override fun cacheKey(source: MediaSource.Bilibili): String = when {
        source.bvid != null -> "video:${source.bvid}:${source.part ?: 1}"
        source.avid != null -> "video:av${source.avid}:${source.part ?: 1}"
        source.epId != null -> "ep:${source.epId}"
        source.seasonId != null -> "season:${source.seasonId}"
        source.roomId != null -> "room:${source.roomId}"
        else -> "short:${source.url}"
    }

    override fun fetch(source: MediaSource.Bilibili): PlatformVideoMetadata? =
        BilibiliApi.metadata(source) ?: YtDlpMetadataFallback.fetch(source.url)
}
