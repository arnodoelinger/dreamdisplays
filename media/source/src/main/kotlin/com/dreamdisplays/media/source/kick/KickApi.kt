package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.source.direct.DirectHlsPlaylist
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** A resolved Kick channel or VOD: playable [streams], [metadata], length, and seekability. */
data class KickPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val durationSec: Long?,
    val isSeekable: Boolean,
)

/**
 * Resolves Kick channels and VODs through Kick's public site API — the same JSON the website uses,
 * no API key. The live channel endpoint (`api/v2/channels/<slug>`) carries the HLS `playback_url`
 * plus live metadata; the VOD endpoint (`api/v1/video/<uuid>`) carries the VOD `source` playlist.
 *
 * Kick sits behind Cloudflare, which rejects datacenter IPs — but resolution runs on the player's
 * own machine, so a residential client usually passes. When it does not, the resolver chain still
 * falls through to `yt-dlp`, so Kick keeps working either way. :)
 *
 * @since 1.9.0
 */
object KickApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/KickApi")

    /** Cloudflare turns away requests that do not look like a browser, so send browser headers. */
    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept" to "application/json",
        "Referer" to "https://kick.com/",
    )

    /** Resolves [source] (a live channel or a VOD), or null when Kick has nothing playable for it. */
    fun resolve(source: MediaSource.Kick): KickPlayback? = when {
        source.videoUuid != null -> resolveVod(source.videoUuid!!)
        source.channel != null -> resolveLive(source.channel!!)
        else -> null
    }

    /** Metadata-only lookup for the cache. */
    fun metadata(source: MediaSource.Kick): PlatformVideoMetadata? = resolve(source)?.metadata

    /** Live-channel lookup used both to resolve playback and to answer search-by-name. */
    fun resolveLive(slug: String): KickPlayback? {
        val root = getJson("https://kick.com/api/v2/channels/$slug") ?: return null
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
            return KickPlayback(streams = emptyList(), metadata = metadata, durationSec = null, isSeekable = false)
        }
        return KickPlayback(
            streams = hlsStreams(playbackUrl),
            metadata = metadata,
            durationSec = null,
            isSeekable = false,
        )
    }

    private fun resolveVod(uuid: String): KickPlayback? {
        val root = getJson("https://kick.com/api/v1/video/$uuid") ?: return null
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
        return KickPlayback(
            streams = hlsStreams(source),
            metadata = metadata,
            durationSec = durationSec,
            isSeekable = true,
        )
    }

    /**
     * Turns an HLS master URL into the rendition ladder so the quality slider works, falling back to
     * the single master URL when the master cannot be fetched or is a plain media playlist (the
     * decoder still opens that and auto-selects a variant).
     */
    private fun hlsStreams(masterUrl: String): List<MediaStream> {
        val parsed = runCatching {
            val text = DreamHttpClient.readText(
                masterUrl,
                DreamHttpClient.RequestOptions(headers = HEADERS, readTimeoutMs = 8_000, callTimeoutMs = 10_000),
            )
            if (DirectHlsPlaylist.looksLikePlaylist(text)) DirectHlsPlaylist.parse(text, masterUrl) else null
        }.getOrNull()

        val variants = parsed?.variants.orEmpty()
        if (variants.isEmpty()) return listOf(muxed(masterUrl))
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

    /** GETs [url] and parses it as a JSON object, or null on any failure (offline, blocked, 404). */
    private fun getJson(url: String): JsonObject? = runCatching {
        val body = DreamHttpClient.readText(
            url,
            DreamHttpClient.RequestOptions(headers = HEADERS, connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
        )
        DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
    }.onFailure { logger.debug("Kick API fetch failed for {}: {}.", url, it.message) }.getOrNull()
}
