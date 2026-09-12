package com.dreamdisplays.media.source.twitch

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.model.MediaMetadata
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.api.media.stream.model.MediaStream
import com.dreamdisplays.api.media.stream.model.MediaStreamType
import com.dreamdisplays.media.source.platform.LiveAwareResolvedMediaCache
import com.dreamdisplays.media.source.youtube.YtDlpResolver
import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-process Twitch stream resolver: one GQL round trip for the playback access token (plus metadata), then a direct
 * usher request for the stream URLs, no `yt-dlp` subprocess.
 */
object TwitchResolver : MediaResolverService {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Above [YtDlpResolver] (0) so the subprocess is only reached when this path fails. */
    override val priority: Int = 10

    /**
     * Live results are cached just long enough to absorb the prefetch->resolve double call and a quick pause-resume;
     * VOD / clip URLs are token-signed too but stable for much longer.
     */
    private val cache = LiveAwareResolvedMediaCache(maxSize = 100, liveTtlSeconds = 25, staticTtlMinutes = 30)

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.Twitch

    /** Warms the token + playlist cache in the background (the registry already runs this off-thread). */
    override fun prefetch(source: MediaSource): Boolean {
        val twitch = source as? MediaSource.Twitch ?: return false
        return runCatching { resolveCached(twitch) }.isSuccess
    }

    /**
     * Resolves [source] via GQL + usher. Throws on any failure so the registry falls through to
     * the `yt-dlp` resolver.
     */
    override fun resolve(source: MediaSource): ResolvedMedia {
        val twitch = source as? MediaSource.Twitch
            ?: throw UnsupportedOperationException("$source is not a Twitch source.")
        return resolveCached(twitch)
    }

    /**
     * Drops the cached resolution for [url] when it is a Twitch source. Wired into the player's
     * stall-recovery cache invalidation so a dead playlist URL is re-minted instead of re-served.
     */
    fun invalidate(url: String) {
        val twitch = MediaSource.from(url) as? MediaSource.Twitch ?: return
        TwitchMetadataCache.cacheKey(twitch)?.let(cache::invalidate)
    }

    private fun resolveCached(source: MediaSource.Twitch): ResolvedMedia {
        val key = TwitchMetadataCache.cacheKey(source)
            ?: throw DreamMediaException.NotFound("Unrecognized Twitch URL shape: ${source.url}.")
        cache.get(key)?.let { return it }
        val resolved = when {
            source.channel != null -> resolveLive(source, source.channel!!)
            source.videoId != null -> resolveVod(source, source.videoId!!)
            else -> resolveClip(source, source.clipSlug!!)
        }
        cache.put(key, resolved)
        return resolved
    }

    private fun resolveLive(source: MediaSource.Twitch, login: String): ResolvedMedia {
        val playback = TwitchApi.livePlayback(login)
            ?: throw DreamMediaException.NotFound("Twitch channel \"$login\" is offline or does not exist.")
        val renditions = fetchRenditions(TwitchHls.liveUrl(login, playback.token))
        logger.debug("Resolved live channel {}: {} renditions.", login, renditions.size)
        TwitchMetadataCache.put(source, playback.metadata)
        return ResolvedMedia(
            streams = renditions,
            metadata = playback.metadata.toMediaMetadata(duration = null),
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
                duration = playback.durationSeconds.takeIf { it > 0 }?.seconds,
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
                duration = playback.durationSeconds.takeIf { it > 0 }?.seconds,
            ),
            isLive = false,
            isSeekable = true,
        )
    }

    /** Downloads and parses an usher master playlist into [MediaStream]s; throws when it has no video. */
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
        // The video codec leads the CODECS list; the selector only needs a family hint
        codec = codecs?.substringBefore(','),
        width = width,
        height = height,
        fps = fps,
        bitrate = bandwidthBps,
        audioTrackName = null,
        audioTrackLang = null,
    )

    private fun TwitchMetadata.toMediaMetadata(duration: Duration?): MediaMetadata = MediaMetadata(
        title = title,
        uploader = channelName,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        viewCount = viewCount,
        likeCount = null,
        uploadDate = null,
    )
}
