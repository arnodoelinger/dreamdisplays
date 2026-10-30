package com.dreamdisplays.media.source

import com.dreamdisplays.api.media.source.MediaResolverService
import com.dreamdisplays.api.media.source.MediaResolverProvider
import com.dreamdisplays.media.source.direct.DirectStreamResolver
import com.dreamdisplays.media.source.platform.Platforms
import com.dreamdisplays.media.source.youtube.NewPipeResolver
import com.dreamdisplays.media.source.youtube.YtDlpResolver

/**
 * Built-in resolver chain, fastest first: direct URL probe, then in-process resolvers (`NewPipeExtractor` and every
 * [Platforms] entry), then `yt-dlp` fallback.
 */
object DefaultMediaResolverProvider : MediaResolverProvider {
    override fun resolvers(): List<MediaResolverService> =
        [DirectStreamResolver, NewPipeResolver] + Platforms.all + YtDlpResolver
}
