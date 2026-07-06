package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaResolverProvider
import com.dreamdisplays.media.source.twitch.TwitchResolver
import com.dreamdisplays.media.source.ytdlp.NewPipeResolver
import com.dreamdisplays.media.source.ytdlp.YtDlpResolver

/**
 * Supplies the built-in resolver chain: the fast in-process paths (`NewPipeExtractor` for YouTube,
 * GQL + usher for Twitch), then the `yt-dlp` fallback.
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolver> = listOf(NewPipeResolver, TwitchResolver, YtDlpResolver)
}
