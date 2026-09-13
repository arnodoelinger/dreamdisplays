package com.dreamdisplays.media.source.youtube.newpipe

import com.dreamdisplays.media.source.youtube.ResolverConfig
import com.dreamdisplays.util.net.DreamHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.nio.charset.StandardCharsets

/**
 * Minimal [Downloader] implementation over the shared facade, honoring the configured proxy
 * (same handling as [com.dreamdisplays.media.source.youtube.YouTubeInnerTube]).
 */
internal object YtHttpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val response = DreamHttpClient.execute(
            request.url(),
            DreamHttpClient.RequestOptions(
                method = request.httpMethod(),
                headers = request.headers(),
                body = data,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 15_000,
                proxyUrl = ResolverConfig.ytdlpProxy,
            ),
        )
        return Response(
            response.code,
            response.message,
            response.headers,
            response.body.toString(StandardCharsets.UTF_8),
            response.finalUrl,
        )
    }
}
