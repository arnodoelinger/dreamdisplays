package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.Experimental
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Normalize, classify, and name-for-display custom media URLs (direct links, not platform URLs).
 *
 * @since 1.9.x
 */
@Experimental
object CustomMediaUrls {
    private val VIDEO_EXTENSIONS = setOf(
        "mp4",
        "m4v",
        "webm",
        "mkv",
        "mov",
        "ogv",
        "avi",
        "ts",
        "mts",
        "m2ts",
        "flv",
        "3gp",
        "3g2",
        "wmv",
        "asf",
        "mpg",
        "mpeg",
        "m2v",
        "mxf",
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3",
        "m4a",
        "aac",
        "flac",
        "wav",
        "opus",
        "oga",
        "ogg",
        "wma",
        "alac",
        "aiff",
        "aif",
    )

    private val MANIFEST_EXTENSIONS = mapOf(
        "m3u8" to CustomMediaKind.HLS,
        "m3u" to CustomMediaKind.HLS,
        "mpd" to CustomMediaKind.DASH,
    )

    private const val WRAPPERS = "<>()[]{}\"'`,;"
    private val DRIVE_PATH_ID = Regex("/file/d/([A-Za-z0-9_-]{8,})")

    /** Cleans and rewrites [raw] to playable HTTP(S) URL; applies wrappers, scheme, share-link rewrites. */
    fun normalize(raw: String): String? {
        var value = raw.trim().trim { it in WRAPPERS }.trim()
        if (value.isEmpty()) return null
        if (!value.contains("://")) {
            if (value.substringBefore('/').contains(':')) return null
            val host = value.substringBefore('/')
            if (!value.contains('/') || !host.contains('.')) return null
            value = "https://$value"
        }
        val parsed = MediaHttpUrl.parse(value) ?: return null
        return rewriteShareLink(parsed.uri) ?: parsed.value
    }

    /** Classifies [url] by extension of last path segment (ignoring query); returns UNKNOWN if unrecognized. */
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

    /** Best human label for [url]: decoded file name (no extension) or host; used for custom video title. */
    fun displayName(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val name = uri?.path?.substringAfterLast('/').orEmpty()
        if (name.isBlank()) return hostOf(url) ?: url
        return cleanFileName(name).ifBlank { hostOf(url) ?: url }
    }

    /** Turns file name into readable title: decode, drop extension, replace `_` and `+` with spaces. */
    fun cleanFileName(name: String): String {
        val decoded = runCatching { URLDecoder.decode(name.trim(), StandardCharsets.UTF_8) }.getOrDefault(name.trim())
        val withoutExtension = if (decoded.contains('.')) decoded.substringBeforeLast('.') else decoded
        return withoutExtension.replace('_', ' ').replace('+', ' ').trim()
    }

    private fun rewriteShareLink(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val segments = uri.path?.split('/')?.filter { it.isNotBlank() } ?: []
        return when {
            // Drive share pages ("/file/d/<id>/view") and the legacy "/open?id=" shape
            host == "drive.google.com" || host == "docs.google.com" -> {
                val id = DRIVE_PATH_ID.find(uri.path ?: "")?.groupValues?.get(1)
                    ?: driveIdQueryParam(uri)
                id?.let { "https://drive.usercontent.google.com/download?id=$it&export=download&confirm=t" }
            }

            // Dropbox serves the file itself only with raw=1; ?dl=0/1 still lands on the preview page
            host == "dropbox.com" || host.endsWith(".dropbox.com") -> asDropboxRawLink(uri)

            // github.com/<owner>/<repo>/blob/<ref>/<path> -> raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
            host == "github.com" && segments.size >= 5 && segments[2] == "blob" ->
                "https://raw.githubusercontent.com/" +
                        ([segments[0], segments[1]] + segments.drop(3)).joinToString("/")

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

    private fun driveIdQueryParam(uri: URI): String? =
        uri.query
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "id" }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }

    private fun asDropboxRawLink(uri: URI): String {
        val kept = uri.query
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.filterNot { it.substringBefore('=') == "raw" || it.substringBefore('=') == "dl" }
            ?: []
        val query = (kept + "raw=1").joinToString("&")
        return "${uri.scheme}://${uri.authority}${uri.path.orEmpty()}?$query"
    }
}
