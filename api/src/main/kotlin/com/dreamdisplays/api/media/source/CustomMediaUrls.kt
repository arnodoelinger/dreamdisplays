package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.security.MediaHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Everything the mod knows about "custom" media URLs: the links players paste by hand rather than
 * pick from the suggestions panel.
 *
 * Three jobs, in the order they run:
 * 1. [normalize] repairs what people actually paste - chat decorations, a missing scheme - and
 *    rewrites the share URL of a file host into the direct URL of the file behind it, so a
 *    Google Drive or Dropbox link works without the player knowing anything about those services.
 * 2. [classify] labels the result, which is what [MediaSource.from] uses to decide between the
 *    direct player path and the extractor chain.
 * 3. [displayName] gives the UI something human to show while no metadata exists, since a direct
 *    file has no title, uploader, or thumbnail anywhere to look them up from.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
object CustomMediaUrls {
    /** Containers the player opens directly; everything here is muxed or video-only, never audio-only. */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "ogv", "avi", "ts", "mts", "m2ts",
        "flv", "3gp", "3g2", "wmv", "asf", "mpg", "mpeg", "m2v", "mxf",
    )

    /** Audio-only containers: recognized so the UI can say *why* they are refused, instead of failing to decode. */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "opus", "oga", "ogg", "wma", "alac", "aiff", "aif",
    )

    /** Streaming-manifest extensions, mapped to the kind their content is served as. */
    private val MANIFEST_EXTENSIONS = mapOf(
        "m3u8" to CustomMediaKind.HLS,
        "m3u" to CustomMediaKind.HLS,
        "mpd" to CustomMediaKind.DASH,
    )

    /**
     * Characters chat clients, Discord embeds and forum software wrap URLs in. Stripped from both
     * ends before parsing, because a pasted `<https://.../v.mp4>` is otherwise simply an invalid URL.
     */
    private const val WRAPPERS = "<>()[]{}\"'`,;"

    /** Google Drive file id: the opaque segment in `/file/d/<id>/` or the `id=` query parameter. */
    private val DRIVE_PATH_ID = Regex("/file/d/([A-Za-z0-9_-]{8,})")

    /**
     * Cleans up [raw] and rewrites it to something playable, or returns null when it is not an
     * `http(s)` URL at all. The result is always a valid [MediaHttpUrl] value.
     *
     * Applied in order: strip chat wrappers and whitespace, add the implicit `https://` scheme so a
     * bare `example.com/v.mp4` works, then run the share-link rewrites in [rewriteShareLink].
     */
    fun normalize(raw: String): String? {
        var value = raw.trim().trim { it in WRAPPERS }.trim()
        if (value.isEmpty()) return null
        // A scheme-less paste ("example.com/v.mp4") is the single most common shape after a full URL;
        // anything carrying a non-http scheme (file:, javascript:, magnet:) is rejected below instead.
        if (!value.contains("://")) {
            if (value.substringBefore('/').contains(':')) return null
            // Only a host *and* a path is assumed to be a URL. Without this, a plain search phrase
            // that happens to look like a hostname ("video.mp4", "minecraft") would be parsed as a
            // link and never reach the search service.
            val host = value.substringBefore('/')
            if (!value.contains('/') || !host.contains('.')) return null
            value = "https://$value"
        }
        val parsed = MediaHttpUrl.parse(value) ?: return null
        return rewriteShareLink(parsed.uri) ?: parsed.value
    }

    /**
     * Rewrites a known file host's *share page* URL into the direct URL of the file it hosts, or
     * returns null when [uri] is not one of them (the overwhelmingly common case).
     *
     * These are the hosts players actually use to share a clip with their server, where the link
     * copied from the browser points at an HTML viewer rather than the video.
     */
    private fun rewriteShareLink(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val segments = uri.path?.split('/')?.filter { it.isNotBlank() } ?: emptyList()
        return when {
            // Drive share pages ("/file/d/<id>/view") and the legacy "/open?id=" shape
            host == "drive.google.com" || host == "docs.google.com" -> {
                val id = DRIVE_PATH_ID.find(uri.path ?: "")?.groupValues?.get(1)
                    ?: queryParam(uri, "id")
                id?.let { "https://drive.google.com/uc?export=download&id=$it" }
            }

            // Dropbox serves the file itself only with raw=1; ?dl=0/1 still lands on the preview page
            host == "dropbox.com" || host.endsWith(".dropbox.com") ->
                withQueryParam(uri, "raw", "1", drop = setOf("dl"))

            // github.com/<owner>/<repo>/blob/<ref>/<path> -> raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
            host == "github.com" && segments.size >= 5 && segments[2] == "blob" ->
                "https://raw.githubusercontent.com/" +
                        (listOf(segments[0], segments[1]) + segments.drop(3)).joinToString("/")

            // GitLab uses the same shape with a "/-/" separator and a "raw" verb
            host == "gitlab.com" && segments.contains("-") && segments.contains("blob") ->
                uri.toString().replace("/-/blob/", "/-/raw/")

            // pixeldrain.com/u/<id> is a viewer page; the API serves the bytes
            host == "pixeldrain.com" && segments.size >= 2 && segments[0] == "u" ->
                "https://pixeldrain.com/api/file/${segments[1]}"

            // Imgur's ".gifv" is an HTML wrapper around an mp4 of the same name
            (host == "imgur.com" || host == "i.imgur.com") && (uri.path?.endsWith(".gifv") == true) ->
                "https://i.imgur.com/${segments.last().removeSuffix(".gifv")}.mp4"

            else -> null
        }
    }

    /**
     * Classifies [url] by the extension of its last path segment, ignoring the query string (signed
     * CDN URLs carry the real name in the path and a signature in the query).
     *
     * Returns [CustomMediaKind.UNKNOWN] for anything without a recognized extension, including the
     * extension-less URLs some CDNs hand out — those still reach the direct resolver through
     * [MediaSource.Remote], where the HTTP probe can settle it.
     */
    fun classify(url: String): CustomMediaKind {
        val extension = extensionOf(url) ?: return CustomMediaKind.UNKNOWN
        MANIFEST_EXTENSIONS[extension]?.let { return it }
        return when (extension) {
            in VIDEO_EXTENSIONS -> CustomMediaKind.PROGRESSIVE
            in AUDIO_EXTENSIONS -> CustomMediaKind.AUDIO_ONLY
            else -> CustomMediaKind.UNKNOWN
        }
    }

    /** True when [url] can be handed straight to the player, skipping the extractor chain. */
    fun isDirect(url: String): Boolean = classify(url).isDirect

    /** Lowercase extension of [url]'s last path segment, or null when it has none. */
    fun extensionOf(url: String): String? {
        val path = runCatching { URI(url.trim()).path }.getOrNull() ?: return null
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        return name.substringAfterLast('.').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }

    /** Host of [url] without the `www.` prefix, or null when it cannot be parsed. */
    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /**
     * The best human label for [url]: its percent-decoded file name without the extension, falling
     * back to the host when the path carries no name (a bare domain, or a query-only endpoint).
     *
     * This is what the UI shows as the title of a custom video, since a direct file has no metadata
     * to look one up from.
     */
    fun displayName(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val name = uri?.path?.substringAfterLast('/').orEmpty()
        if (name.isBlank()) return hostOf(url) ?: url
        return cleanFileName(name).ifBlank { hostOf(url) ?: url }
    }

    /**
     * Turns a raw file name into a readable title: percent-decoded, extension dropped, and
     * separator characters (`_`, `+`) turned into spaces. Shared by [displayName] and the
     * `Content-Disposition` filename path so both read the same way.
     */
    fun cleanFileName(name: String): String {
        val decoded = runCatching { URLDecoder.decode(name.trim(), StandardCharsets.UTF_8) }.getOrDefault(name.trim())
        val withoutExtension = if (decoded.contains('.')) decoded.substringBeforeLast('.') else decoded
        return withoutExtension.replace('_', ' ').replace('+', ' ').trim()
    }

    /** Returns the first value of query parameter [name] in [uri], or null when absent. */
    private fun queryParam(uri: URI, name: String): String? =
        uri.query
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }

    /** Rebuilds [uri] with [name]=[value] set and every parameter in [drop] removed. */
    private fun withQueryParam(uri: URI, name: String, value: String, drop: Set<String>): String {
        val kept = uri.query
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.filterNot { it.substringBefore('=') == name || it.substringBefore('=') in drop }
            ?: emptyList()
        val query = (kept + "$name=$value").joinToString("&")
        return "${uri.scheme}://${uri.authority}${uri.path.orEmpty()}?$query"
    }
}
