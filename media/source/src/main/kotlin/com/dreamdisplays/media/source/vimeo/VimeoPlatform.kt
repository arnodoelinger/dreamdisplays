package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.source.platform.Platform
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import kotlin.time.Duration.Companion.minutes

/**
 * Vimeo: one call to the player config endpoint.
 *
 * @see VimeoApi
 */
object VimeoPlatform : Platform<MediaSource.Vimeo, PlatformVideoMetadata>(
    "Vimeo", MediaSource.Vimeo::class.java, VimeoMetadataCache, staticTtl = 10.minutes,
) {
    override fun resolveUncached(source: MediaSource.Vimeo): ResolvedMedia {
        val playback = VimeoApi.resolve(source)
            ?: throw DreamMediaException.NotFound("Vimeo video \"${source.videoId}\" is unavailable or private.")
        check(playback.streams.any { it.type.hasVideo }) { "Vimeo returned no playable video for ${source.videoId}." }
        return playback.toResolvedMedia(source, "Vimeo video \"${source.videoId}\" has no playable stream.", VimeoMetadataCache)
    }
}
