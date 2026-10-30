package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.source.platform.PlatformHttp
import com.dreamdisplays.media.source.platform.PlatformPlayback
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.util.*
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.serialization.json.JsonObject

/**
 * Resolves Vimeo videos through the public player config endpoint — the same JSON Vimeo's own embedded player fetches,
 * so no API key or auth is needed.
 */
object VimeoApi {
    private val HEADERS = DreamHttpClient.headersOf(
        "User-Agent" to PlatformHttp.BROWSER_USER_AGENT,
        "Referer" to "https://vimeo.com/",
    )

    /** Fetches and parses the player config for [source], or null when it is unavailable. */
    fun resolve(source: MediaSource.Vimeo): PlatformPlayback? {
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
            val chosen =
                defaultCdn?.let { cdns?.obj(it) } ?: cdns?.values?.firstNotNullOfOrNull { it.asJsonObjectOrNull() }
            chosen?.optString("url") ?: chosen?.optString("avc_url")
        }

        // Prefer the progressive ladder (seekable files) and fall back to the HLS master
        val streams = when {
            progressive.isNotEmpty() -> progressive.sortedByDescending { it.height ?: 0 }
            hlsUrl != null -> [hlsStream(hlsUrl)]
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

        return PlatformPlayback(
            streams = streams,
            metadata = metadata,
            // Any on-demand Vimeo video seeks (progressive by byte range, HLS by the decoder); only a
            // live event cannot.
            isSeekable = metadata.isLive.not(),
        )
    }

    /** Metadata-only lookup for the cache: the same config call, without building streams. */
    fun metadata(source: MediaSource.Vimeo): PlatformVideoMetadata? = resolve(source)?.metadata

    private fun fetchConfig(videoId: String, hash: String?): JsonObject? =
        PlatformHttp.getJson("https://player.vimeo.com/video/$videoId/config" + hash?.let { "?h=$it" }.orEmpty(), HEADERS)

    private fun bestThumbnail(video: JsonObject?): String? {
        val thumbs = video?.obj("thumbs")
        return thumbs?.optString("1280") ?: thumbs?.optString("960") ?: thumbs?.optString("640")
        ?: thumbs?.optString("base") ?: video?.optString("thumbnail_url")
    }

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
