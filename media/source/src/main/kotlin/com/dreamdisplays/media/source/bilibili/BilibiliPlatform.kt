package com.dreamdisplays.media.source.bilibili

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.source.platform.Platform
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata

/**
 * Bilibili: VODs, bangumi episodes, and live rooms through the web API.
 *
 * @see BilibiliApi
 */
object BilibiliPlatform :
    Platform<MediaSource.Bilibili, PlatformVideoMetadata>("Bilibili", MediaSource.Bilibili::class.java, BilibiliMetadataCache) {
    override fun resolveUncached(source: MediaSource.Bilibili): ResolvedMedia {
        val playback = BilibiliApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Bilibili video/room could not be reached.")
        val offline = if (source.roomId != null) "This Bilibili live room is offline right now."
        else "This Bilibili video is unavailable or private."
        return playback.toResolvedMedia(source, offline, BilibiliMetadataCache)
    }
}
