package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.source.platform.Platform
import com.dreamdisplays.media.source.youtube.YtDlpResolver
import com.dreamdisplays.util.net.DreamHttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** In-process Twitch stream resolver. */
object TwitchPlatform : Platform<MediaSource.Twitch, TwitchMetadata>(
    "Twitch", MediaSource.Twitch::class.java, TwitchMetadataCache, maxCached = 100,
) {
    override fun resolveUncached(source: MediaSource.Twitch): ResolvedMedia = when {
        source.channel != null -> resolveLive(source, source.channel!!)
        source.videoId != null -> resolveVod(source, source.videoId!!)
        else -> resolveClip(source, source.clipSlug!!)
    }

    private fun resolveLive(source: MediaSource.Twitch, login: String): ResolvedMedia {
        val playback = TwitchApi.livePlayback(login)
            ?: throw DreamMediaException.NotFound("Twitch channel \"$login\" is offline or does not exist.")
        val renditions = fetchRenditions(TwitchHls.liveUrl(login, playback.token))
        logger.debug("Resolved live channel {}: {} renditions.", login, renditions.size)
        TwitchMetadataCache.put(source, playback.metadata)
        return ResolvedMedia(
            streams = renditions,
            metadata = playback.metadata.toMediaMetadata(durationMs = null),
            isLive = true,
            isSeekable = false,
        )
    }

    private fun resolveVod(source: MediaSource.Twitch, id: String): ResolvedMedia {
        val playback = TwitchApi.vodPlayback(id)
            ?: throw DreamMediaException.NotFound("Twitch VOD \"$id\" does not exist or is sub-only.")
        val renditions = fetchRenditions(TwitchHls.vodUrl(id, playback.token))
        logger.debug("Resolved VOD {}: {} renditions.", id, renditions.size)
        TwitchMetadataCache.put(source, playback.metadata)
        return ResolvedMedia(
            streams = renditions,
            metadata = playback.metadata.toMediaMetadata(
                durationMs = playback.durationSeconds.takeIf { it > 0 }?.times(1000L),
            ),
            isLive = false,
            isSeekable = true,
        )
    }

    private fun resolveClip(source: MediaSource.Twitch, slug: String): ResolvedMedia {
        val playback = TwitchApi.clipPlayback(slug)
            ?: throw DreamMediaException.NotFound("Twitch clip \"$slug\" does not exist or has no renditions.")
        val sig = URLEncoder.encode(playback.token.signature, StandardCharsets.UTF_8)
        val token = URLEncoder.encode(playback.token.value, StandardCharsets.UTF_8)
        val streams = playback.qualities.map { quality ->
            MediaStream(
                url = "${quality.sourceUrl}?sig=$sig&token=$token",
                type = MediaStreamType.VIDEO_AUDIO,
                codec = null,
                width = null,
                height = quality.quality.toIntOrNull(),
                fps = quality.frameRate,
                bitrate = null,
                audioTrackName = null,
                audioTrackLang = null,
            )
        }
        TwitchMetadataCache.put(source, playback.metadata)
        return ResolvedMedia(
            streams = streams,
            metadata = playback.metadata.toMediaMetadata(
                durationMs = playback.durationSeconds.takeIf { it > 0 }?.times(1000L),
            ),
            isLive = false,
            isSeekable = true,
        )
    }

    private fun fetchRenditions(playlistUrl: String): List<MediaStream> {
        val playlist = DreamHttpClient.readText(
            playlistUrl,
            DreamHttpClient.RequestOptions(readTimeoutMs = 10_000L, callTimeoutMs = 12_000L),
        )
        val streams = TwitchHls.parseMaster(playlist).map { it.toMediaStream() }
        check(streams.any { it.type.hasVideo }) { "Usher master playlist contained no video renditions." }
        return streams
    }

    private fun TwitchHls.Rendition.toMediaStream(): MediaStream = MediaStream(
        url = url,
        type = if (isAudioOnly) MediaStreamType.AUDIO else MediaStreamType.VIDEO_AUDIO,
        codec = codecs?.substringBefore(','),
        width = width,
        height = height,
        fps = fps,
        bitrate = bandwidthBps,
        audioTrackName = null,
        audioTrackLang = null,
    )

    private fun TwitchMetadata.toMediaMetadata(durationMs: Long?): MediaMetadata = MediaMetadata(
        title = title,
        uploader = channelName,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        likeCount = null,
        uploadDate = null,
    )
}
