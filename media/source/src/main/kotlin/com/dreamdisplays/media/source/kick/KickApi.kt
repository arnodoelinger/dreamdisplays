package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.source.direct.DirectHlsPlaylist
import com.dreamdisplays.media.source.platform.PlatformHttp
import com.dreamdisplays.media.source.platform.PlatformPlayback
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.util.*
import com.dreamdisplays.util.net.DreamHttpClient

/** Resolves Kick channels and VODs through Kick's public site API — the same JSON the website uses, no API key or auth required. */
object KickApi {
    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to PlatformHttp.BROWSER_USER_AGENT,
        "Accept" to "application/json",
        "Referer" to "https://kick.com/",
    )

    /** Resolves [source] (a live channel or a VOD), or null when Kick has nothing playable for it. */
    fun resolve(source: MediaSource.Kick): PlatformPlayback? = when {
        source.videoUuid != null -> resolveVod(source.videoUuid!!)
        source.channel != null -> resolveLive(source.channel!!)
        else -> null
    }

    /** Metadata-only lookup for the cache. */
    fun metadata(source: MediaSource.Kick): PlatformVideoMetadata? = resolve(source)?.metadata

    /** Live-channel lookup used both to resolve playback and to answer search-by-name. */
    fun resolveLive(slug: String): PlatformPlayback? {
        val root = PlatformHttp.getJson("https://kick.com/api/v2/channels/$slug", HEADERS) ?: return null
        val livestream = root.obj("livestream")
        val isLive = livestream?.optBoolean("is_live") == true
        val playbackUrl = root.optString("playback_url")

        val metadata = PlatformVideoMetadata(
            title = livestream?.optString("session_title") ?: root.obj("user")?.optString("username"),
            uploader = root.obj("user")?.optString("username") ?: slug,
            thumbnailUrl = livestream?.obj("thumbnail")?.let { it.optString("url") ?: it.optString("src") },
            uploaderAvatarUrl = root.obj("user")?.optString("profile_pic"),
            viewCount = livestream?.optLong("viewer_count"),
            durationSec = null,
            isLive = isLive,
        )
        // Offline channels have no playback URL; still return the metadata so the card can say so
        if (!isLive || playbackUrl == null) {
            return PlatformPlayback(streams = [], metadata = metadata, isSeekable = false)
        }
        return PlatformPlayback(streams = hlsStreams(playbackUrl), metadata = metadata, isSeekable = false)
    }

    private fun resolveVod(uuid: String): PlatformPlayback? {
        val root = PlatformHttp.getJson("https://kick.com/api/v1/video/$uuid", HEADERS) ?: return null
        val source = root.optString("source") ?: return null
        val livestream = root.obj("livestream")
        val channel = livestream?.obj("channel")
        val durationSec = livestream?.optLong("duration")?.takeIf { it > 0 }?.let { it / 1000 }

        val metadata = PlatformVideoMetadata(
            title = livestream?.optString("session_title"),
            uploader = channel?.obj("user")?.optString("username") ?: channel?.optString("slug"),
            thumbnailUrl = livestream?.obj("thumbnail")?.let { it.optString("src") ?: it.optString("url") },
            uploaderAvatarUrl = channel?.obj("user")?.optString("profile_pic"),
            viewCount = null,
            durationSec = durationSec,
            isLive = false,
        )
        return PlatformPlayback(streams = hlsStreams(source), metadata = metadata, isSeekable = true)
    }

    private fun hlsStreams(masterUrl: String): List<MediaStream> {
        val parsed = runCatching {
            val text = DreamHttpClient.readText(
                masterUrl,
                DreamHttpClient.RequestOptions(headers = HEADERS, readTimeoutMs = 8_000, callTimeoutMs = 10_000),
            )
            if (DirectHlsPlaylist.looksLikePlaylist(text)) DirectHlsPlaylist.parse(text, masterUrl) else null
        }.getOrNull()

        val variants = parsed?.variants.orEmpty()
        if (variants.isEmpty()) return [muxed(masterUrl)]
        return variants.map { v ->
            MediaStream(
                url = v.url,
                type = MediaStreamType.VIDEO_AUDIO,
                codec = v.codecs,
                width = v.width,
                height = v.height,
                fps = v.fps,
                bitrate = v.bandwidthBps,
                audioTrackName = null,
                audioTrackLang = null,
            )
        }
    }

    private fun muxed(url: String) = MediaStream(
        url = url, type = MediaStreamType.VIDEO_AUDIO,
        codec = null, width = null, height = null, fps = null, bitrate = null,
        audioTrackName = null, audioTrackLang = null,
    )
}
