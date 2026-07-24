package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaResolverProvider
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.kick.KickResolver
import com.dreamdisplays.media.source.twitch.TwitchResolver
import com.dreamdisplays.media.source.vimeo.VimeoResolver
import com.dreamdisplays.media.source.ytdlp.NewPipeResolver
import com.dreamdisplays.media.source.ytdlp.YtDlpResolver

/**
 * Supplies the built-in resolver chain, fastest path first: direct media URLs (one HTTP probe),
 * then the in-process platform extractors (`NewPipeExtractor` for YouTube, GQL + usher for Twitch,
 * player-config for Vimeo, site-API for Kick), then the `yt-dlp` subprocess as the universal
 * fallback that also covers the long tail of sites none of the fast paths handle.
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolver> = listOf(
        DirectStreamResolver,
        NewPipeResolver,
        TwitchResolver,
        VimeoResolver,
        KickResolver,
        YtDlpResolver,
    )
}
