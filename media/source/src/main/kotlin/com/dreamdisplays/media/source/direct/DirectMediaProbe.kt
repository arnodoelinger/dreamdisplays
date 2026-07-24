package com.dreamdisplays.media.source.direct

import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A single cheap HTTP round trip that answers everything the direct resolver needs to know about a
 * user-pasted URL: is it reachable, is it media at all, how big is it, and may it be seeked.
 *
 * `HEAD` is tried first because it costs no body. A meaningful minority of file hosts (and most CDN
 * signed-URL setups) answer `HEAD` with 403/405 while serving `GET` perfectly, so a failed or
 * unhelpful `HEAD` retries as a one-byte ranged `GET` — which also confirms range support directly
 * instead of trusting the `Accept-Ranges` header. When the server is coy about the content type, a
 * short magic-byte sniff settles it, so extension-less CDN links still resolve.
 *
 * The caller is expected to pass a URL already run through the SSRF guard's redirect-safe resolver,
 * so every request here is made with redirects disabled — the guard, not `OkHttp`, decides which
 * hosts may be reached.
 */
internal object DirectMediaProbe {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/DirectMediaProbe")

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 8_000L

    /** Bytes read for the container magic-number sniff. */
    private const val SNIFF_BYTES = 64

    /**
     * What the server said about the URL.
     *
     * @property finalUrl the URL that was actually probed (already redirect-resolved by the caller).
     * @property contentType lowercase MIME type without parameters, or null when unstated.
     * @property contentLength total byte length when known, else null (chunked / live responses).
     * @property acceptsRanges true when the server confirmed byte-range requests, which is what
     * makes a progressive file seekable.
     * @property fileName the name from `Content-Disposition`, when the server offered one.
     */
    data class Result(
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val acceptsRanges: Boolean,
        val fileName: String? = null,
    ) {
        /** True when the MIME type names audio or video (or an HLS / DASH manifest type). */
        val isMediaType: Boolean
            get() {
                val type = contentType ?: return false
                return type.startsWith("video/") || type.startsWith("audio/") ||
                        type in MANIFEST_TYPES || type in TOLERATED_TYPES
            }

        /** True when the server answered with a web page, the classic "that link is not the file" case. */
        val isHtml: Boolean get() = contentType == "text/html" || contentType == "application/xhtml+xml"

        /** True when the MIME type names an audio container, so there is no picture to show. */
        val isAudioType: Boolean get() = contentType?.startsWith("audio/") == true
    }

    /** MIME types HLS / DASH manifests are served as. */
    private val MANIFEST_TYPES = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
        "application/dash+xml",
    )

    /**
     * Generic types a correctly-served media file is nonetheless often labelled with: object
     * storage defaults to `application/octet-stream`, and some hosts mislabel `.mp4` as
     * `binary/octet-stream`. Accepted only because the URL already classified as media by
     * extension - the caller never probes an unclassified URL expecting these to pass.
     */
    private val TOLERATED_TYPES = setOf("application/octet-stream", "binary/octet-stream")

    /**
     * Probes [url], or returns null when the request failed outright (DNS, TLS, timeout, 4xx/5xx).
     * Never throws: a failed probe means "the direct path cannot answer for this URL", and the
     * caller decides whether to refuse or fall through to the extractor chain.
     */
    fun probe(url: String): Result? {
        head(url)?.takeIf { it.contentType != null || it.contentLength != null }?.let { base ->
            // A HEAD carries no body, so a generic / missing content type needs its own sniff GET
            if (base.contentType == null || base.contentType in TOLERATED_TYPES) {
                sniffContentType(url)?.let { return base.copy(contentType = it) }
            }
            return base
        }

        // The ranged GET both confirms range support and, crucially, already returns the leading
        // bytes — so the magic-byte sniff reads them from here instead of re-fetching the URL
        val (base, body) = rangedGet(url) ?: return null
        if (base.contentType == null || base.contentType in TOLERATED_TYPES) {
            magicContentType(body)?.let { return base.copy(contentType = it) }
        }
        return base
    }

    /** `HEAD` probe; null when the server refuses the method or the request fails. */
    private fun head(url: String): Result? = runCatching {
        val response = DreamHttpClient.execute(url, requestOptions("HEAD"))
        if (!response.isSuccessful) return@runCatching null
        response.toResult()
    }.onFailure { logger.debug("HEAD probe failed for {}: {}.", url.take(120), it.message) }.getOrNull()

    /**
     * Ranged `GET` fallback, returning both the [Result] and the bytes read. A `206 Partial Content`
     * reply is itself proof of range support, and its `Content-Range` header carries the full length
     * that `Content-Length` cannot.
     */
    private fun rangedGet(url: String): Pair<Result, ByteArray>? = runCatching {
        val response = DreamHttpClient.executeLimited(
            url,
            maxBytes = SNIFF_BYTES,
            options = requestOptions("GET", range = "bytes=0-${SNIFF_BYTES - 1}"),
        )
        if (!response.isSuccessful) return@runCatching null
        val contentRange = response.headerValue("content-range")
        val result = response.toResult(
            contentLengthOverride = contentRange?.substringAfter('/', "")?.toLongOrNull()
                ?: response.headerValue("content-length")?.toLongOrNull()?.takeIf { response.code != 206 },
            acceptsRangesOverride = response.code == 206 ||
                    response.headerValue("accept-ranges")?.contains("bytes", ignoreCase = true) == true,
        )
        result to response.body
    }.onFailure { logger.debug("Ranged GET probe failed for {}: {}.", url.take(120), it.message) }.getOrNull()

    /**
     * Reads the first [SNIFF_BYTES] bytes and infers a media content type from the container magic
     * number, or null when the bytes are not recognizably media. Used only on the HEAD path; the
     * ranged path sniffs from the bytes it already fetched.
     */
    private fun sniffContentType(url: String): String? = runCatching {
        val response = DreamHttpClient.executeLimited(
            url, maxBytes = SNIFF_BYTES, options = requestOptions("GET", range = "bytes=0-${SNIFF_BYTES - 1}"),
        )
        if (!response.isSuccessful) return@runCatching null
        magicContentType(response.body)
    }.onFailure { logger.debug("Sniff failed for {}: {}.", url.take(120), it.message) }.getOrNull()

    /** Maps a leading byte pattern to a content type for the container families players paste. */
    private fun magicContentType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun ascii(offset: Int, len: Int) = String(bytes, offset, len, StandardCharsets.US_ASCII)
        return when {
            ascii(4, 4) == "ftyp" -> "video/mp4"                                            // ISO-BMFF (mp4 / mov / m4v)
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
                    bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "video/webm"  // Matroska / WebM (EBML)
            ascii(0, 3) == "FLV" -> "video/x-flv"
            ascii(0, 4) == "OggS" -> "video/ogg"
            ascii(0, 7) == "#EXTM3U" -> "application/vnd.apple.mpegurl"                     // HLS playlist
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && ascii(8, 4) == "AVI " -> "video/x-msvideo" // RIFF...AVI
            else -> null
        }
    }

    private fun requestOptions(method: String, range: String? = null) = DreamHttpClient.RequestOptions(
        method = method,
        headers = range?.let { DreamHttpClient.headersOf("Range" to it) } ?: emptyMap(),
        connectTimeoutMs = CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
        // The caller already redirect-resolved the URL through the SSRF guard; do not let OkHttp
        // silently follow a fresh redirect to an unvalidated (possibly internal) host.
        followRedirects = false,
    )

    /** Builds a [Result] from this response, letting the ranged path override length / range facts. */
    private fun DreamHttpClient.HttpResponse.toResult(
        contentLengthOverride: Long? = headerValue("content-length")?.toLongOrNull(),
        acceptsRangesOverride: Boolean = headerValue("accept-ranges")?.contains("bytes", ignoreCase = true) == true,
    ) = Result(
        finalUrl = finalUrl,
        contentType = headerValue("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT),
        contentLength = contentLengthOverride,
        acceptsRanges = acceptsRangesOverride,
        fileName = fileNameFrom(headerValue("content-disposition")),
    )

    /** Extracts a filename from a `Content-Disposition` header value, decoding `filename*` when present. */
    private fun fileNameFrom(disposition: String?): String? {
        if (disposition == null) return null
        // RFC 5987 extended form: filename*=UTF-8''percent%20encoded.mp4
        Regex("filename\\*=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1], StandardCharsets.UTF_8) }
                .getOrNull()?.takeIf { name -> name.isNotBlank() }
        }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
            return it.groupValues[1].trim().takeIf { name -> name.isNotBlank() }
        }
        return null
    }

    /** First value of header [name], matched case-insensitively as HTTP header names are. */
    private fun DreamHttpClient.HttpResponse.headerValue(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}
