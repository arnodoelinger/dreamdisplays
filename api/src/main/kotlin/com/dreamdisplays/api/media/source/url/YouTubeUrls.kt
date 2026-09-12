package com.dreamdisplays.api.media.source.url

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.search.model.YouTubeVideoId
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Canonical YouTube URL parsing and building.
 *
 * @since 1.0.x
 */
@Unstable
object YouTubeUrls {
    private val VIDEO_PATH_PREFIXES = setOf("shorts", "embed", "live", "v")

    /** Returns the watch-page URL for [videoId]. */
    fun watchUrl(videoId: YouTubeVideoId): String = "https://www.youtube.com/watch?v=${videoId.value}"

    /** Returns the watch-page URL for [videoId]. */
    fun watchUrl(videoId: String): String = watchUrl(YouTubeVideoId.require(videoId))

    /** Returns the high-quality (480x360) thumbnail URL for [videoId]; always available, unlike sd / maxres. */
    fun thumbnailUrl(videoId: YouTubeVideoId): String = "https://i.ytimg.com/vi/${videoId.value}/hqdefault.jpg"

    /** Returns the high-quality (480x360) thumbnail URL for [videoId]; always available, unlike sd / maxres. */
    fun thumbnailUrl(videoId: String): String = thumbnailUrl(YouTubeVideoId.require(videoId))

    /** Returns the compact (320x180) thumbnail URL for [videoId]; clean 16:9 and always available. */
    fun mqThumbnailUrl(videoId: YouTubeVideoId): String = "https://i.ytimg.com/vi/${videoId.value}/mqdefault.jpg"

    /** Returns the compact (320x180) thumbnail URL for [videoId]; clean 16:9 and always available. */
    fun mqThumbnailUrl(videoId: String): String = mqThumbnailUrl(YouTubeVideoId.require(videoId))

    /** Returns max-res thumbnail URL; verify response since YouTube 200s with placeholder if missing. */
    fun maxResThumbnailUrl(videoId: YouTubeVideoId): String =
        "https://i.ytimg.com/vi/${videoId.value}/maxresdefault.jpg"

    /** Returns the max-resolution thumbnail URL for [videoId]; see the [YouTubeVideoId] overload for caveats. */
    fun maxResThumbnailUrl(videoId: String): String = maxResThumbnailUrl(YouTubeVideoId.require(videoId))

    /** Extracts video ID from URL (youtube.com/watch, youtu.be, shorts, embed, live) or bare ID. */
    fun extractVideoId(url: String?): String? = extractVideoIdTyped(url)?.value

    /** Extracts typed video ID from full/schemeless URL or bare ID. */
    fun extractVideoIdTyped(input: String?): YouTubeVideoId? {
        val value = input?.trim() ?: return null
        if (value.isEmpty()) return null

        YouTubeVideoId.parse(value)?.let { return it }

        val uri = parseInputUri(value) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val pathSegments = uri.path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (isHost(host, "youtu.be")) {
            return pathSegments.firstNotNullOfOrNull { YouTubeVideoId.parse(it) }
        }

        if (!isHost(host, "youtube.com") && !isHost(host, "youtube-nocookie.com")) {
            return null
        }

        videoIdQueryParameter(uri.rawQuery)?.let { YouTubeVideoId.parse(it) }?.let { return it }

        if (pathSegments.size >= 2 && pathSegments[0].lowercase(Locale.ROOT) in VIDEO_PATH_PREFIXES) {
            return YouTubeVideoId.parse(pathSegments[1])
        }

        if (pathSegments.size == 1) {
            return YouTubeVideoId.parse(pathSegments[0])
        }

        return null
    }

    /** Parse input as a URI, falling back to https if no scheme is specified. */
    private fun parseInputUri(input: String): URI? {
        val direct = runCatching { URI(input) }.getOrNull()
        if (direct?.scheme != null) return direct
        return runCatching { URI("https://$input") }.getOrNull()
    }

    /** Returns true if [host] is either [root] or [root]."com". */
    private fun isHost(host: String, root: String): Boolean =
        host == root || host.endsWith(".$root")

    /** Returns the `v` (video id) query parameter value in [rawQuery], or null if not found. */
    private fun videoIdQueryParameter(rawQuery: String?): String? =
        rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull { part ->
                val separator = part.indexOf('=')
                val rawKey = if (separator >= 0) part.substring(0, separator) else part
                if (decode(rawKey) != "v") return@firstNotNullOfOrNull null
                val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
                decode(rawValue)
            }

    /** Decodes [value] using UTF-8. */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
