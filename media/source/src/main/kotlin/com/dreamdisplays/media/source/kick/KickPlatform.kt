package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.media.source.platform.Platform
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata

/**
 * Kick: one site-API call.
 *
 * @see KickApi
 */
object KickPlatform : Platform<MediaSource.Kick, PlatformVideoMetadata>("Kick", MediaSource.Kick::class.java, KickMetadataCache) {
    override fun resolveUncached(source: MediaSource.Kick): ResolvedMedia {
        val playback = KickApi.resolve(source) ?: throw DreamMediaException.NotFound("Kick channel/video could not be reached.")
        val offline = if (source.channel != null) "This Kick channel is offline right now." else "This Kick video has no playable stream."
        return playback.toResolvedMedia(source, offline, KickMetadataCache)
    }
}
