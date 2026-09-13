package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.service.MediaResolverProvider
import com.dreamdisplays.media.source.bilibili.BilibiliResolver
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.kick.KickResolver
import com.dreamdisplays.media.source.twitch.TwitchResolver
import com.dreamdisplays.media.source.vimeo.VimeoResolver
import com.dreamdisplays.media.source.youtube.NewPipeResolver
import com.dreamdisplays.media.source.youtube.YtDlpResolver

/**
 * Built-in resolver chain, fastest first: direct URL probe, then in-process platform resolvers (`NewPipeExtractor`, Twitch, Vimeo, Kick,
 * Bilibili), then `yt-dlp` fallback.
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolverService> = listOf(
        DirectStreamResolver,
        NewPipeResolver,
        TwitchResolver,
        VimeoResolver,
        KickResolver,
        BilibiliResolver,
        YtDlpResolver,
    )
}
