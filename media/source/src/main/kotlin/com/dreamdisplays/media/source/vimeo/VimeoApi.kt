package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.util.*
import com.dreamdisplays.util.json.DreamJson
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** A resolved Vimeo video: its playable [streams], [metadata], length, and seekability. */
data class VimeoPlayback(
    val streams: List<MediaStream>,
    val metadata: PlatformVideoMetadata,
    val durationSec: Long?,
    val isSeekable: Boolean,
)

/**
 * Resolves Vimeo videos through the public player config endpoint — the same JSON Vimeo's own
 * embedded player fetches, needing no API key.
 *
 * One request to `player.vimeo.com/video/<id>/config` returns both the metadata (title, owner,
 * thumbnails, duration) and the playable files: a ladder of progressive MP4s (ideal — the player
 * opens them directly and seeks by byte range) plus an HLS master as the fallback for videos Vimeo
 * only streams adaptively. This is why [VimeoResolver] can skip the `yt-dlp` subprocess.
 *
 * @since 1.9.0
 */
object VimeoApi {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/VimeoApi")

    /** Vimeo's config endpoint gates some videos on a browser-like request, so mimic one. */
    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer" to "https://vimeo.com/",
    )

    /** Fetches and parses the player config for [source], or null when it is unavailable. */
    fun resolve(source: MediaSource.Vimeo): VimeoPlayback? {
        val config = fetchConfig(source.videoId, source.hash) ?: return null
        val request = config.obj("request")
        val files = request?.obj("files")

        val progressive = files?.array("progressive")?.mapNotNull { entry ->
            val obj = entry.asJsonObjectOrNull() ?: return@mapNotNull null
            val url = obj.optString("url") ?: return@mapNotNull null
            MediaStream(
                url = url,
                type = MediaStreamType.VIDEO_AUDIO,
                codec = obj.optString("codec"),
                width = obj.optInt("width"),
                height = obj.optInt("height") ?: obj.optString("quality")?.removeSuffix("p")?.toIntOrNull(),
                fps = obj.optDouble("fps"),
                bitrate = null,
                audioTrackName = null,
                audioTrackLang = null,
            )
        }.orEmpty()

        val hlsUrl = files?.obj("hls")?.let { hls ->
            val cdns = hls.obj("cdns")
            val defaultCdn = hls.optString("default_cdn")
            val chosen = defaultCdn?.let { cdns?.obj(it) } ?: cdns?.values?.firstNotNullOfOrNull { it.asJsonObjectOrNull() }
            chosen?.optString("url") ?: chosen?.optString("avc_url")
        }

        // Prefer the progressive ladder (seekable files) and fall back to the HLS master
        val streams = when {
            progressive.isNotEmpty() -> progressive.sortedByDescending { it.height ?: 0 }
            hlsUrl != null -> listOf(hlsStream(hlsUrl))
            else -> return null
        }

        val video = config.obj("video")
        val durationSec = video?.optLong("duration")?.takeIf { it > 0 }
        val metadata = PlatformVideoMetadata(
            title = video?.optString("title"),
            uploader = video?.obj("owner")?.optString("name"),
            thumbnailUrl = bestThumbnail(video),
            uploaderAvatarUrl = video?.obj("owner")?.obj("portrait")?.let { largestPortrait(it) },
            viewCount = null,
            durationSec = durationSec,
            isLive = video?.optBoolean("live_event") == true,
        )

        return VimeoPlayback(
            streams = streams,
            metadata = metadata,
            durationSec = durationSec,
            // Any on-demand Vimeo video seeks (progressive by byte range, HLS by the decoder); only a
            // live event cannot.
            isSeekable = metadata.isLive.not(),
        )
    }

    /** Metadata-only lookup for the cache: the same config call, without building streams. */
    fun metadata(source: MediaSource.Vimeo): PlatformVideoMetadata? = resolve(source)?.metadata

    /** GETs and parses the player config JSON for [videoId] (with optional unlisted [hash]). */
    private fun fetchConfig(videoId: String, hash: String?): JsonObject? {
        val url = buildString {
            append("https://player.vimeo.com/video/").append(videoId).append("/config")
            if (hash != null) append("?h=").append(hash)
        }
        return runCatching {
            val body = DreamHttpClient.readText(
                url,
                DreamHttpClient.RequestOptions(headers = HEADERS, connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
            )
            DreamJson.compact.parseToJsonElement(body).asJsonObjectOrNull()
        }.onFailure { logger.debug("Vimeo config fetch failed for {}: {}", videoId, it.message) }.getOrNull()
    }

    /** Picks the largest thumbnail Vimeo offers in the `thumbs` map. */
    private fun bestThumbnail(video: JsonObject?): String? {
        val thumbs = video?.obj("thumbs") ?: return null
        return thumbs.optString("1280") ?: thumbs.optString("960") ?: thumbs.optString("640")
            ?: thumbs.optString("base")
    }

    /** Picks the largest owner-portrait size from the numeric-keyed `portrait` map. */
    private fun largestPortrait(portrait: JsonObject): String? =
        portrait.entries
            .mapNotNull { (key, _) -> key.toIntOrNull()?.let { it to portrait.optString(key) } }
            .filter { it.second != null }
            .maxByOrNull { it.first }
            ?.second

    private fun hlsStream(url: String): MediaStream = MediaStream(
        url = url,
        type = MediaStreamType.VIDEO_AUDIO,
        codec = null, width = null, height = null, fps = null, bitrate = null,
        audioTrackName = null, audioTrackLang = null,
    )
}
