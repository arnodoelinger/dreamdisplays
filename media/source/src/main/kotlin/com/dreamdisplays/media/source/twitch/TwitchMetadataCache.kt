package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache

/** Metadata cache for Twitch channels, VODs, and clips, keyed so the three kinds never collide. */
object TwitchMetadataCache : PlatformMetadataCache<MediaSource.Twitch, TwitchMetadata>("Twitch", TwitchMetadata::isLive) {
    override fun cacheKey(source: MediaSource.Twitch): String? = when {
        source.channel != null -> "channel:${source.channel}"
        source.videoId != null -> "video:${source.videoId}"
        source.clipSlug != null -> "clip:${source.clipSlug}"
        else -> null
    }

    override fun fetch(source: MediaSource.Twitch): TwitchMetadata? = TwitchApi.resolve(source)
}
