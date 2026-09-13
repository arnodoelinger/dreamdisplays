package com.dreamdisplays.media.source.direct

import com.dreamdisplays.util.net.DreamHttpClient
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * A single cheap HTTP round trip that answers everything the direct resolver needs to know about a user-pasted URL.
 */
internal object DirectMediaProbe {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 8_000L
    private const val SNIFF_BYTES = 512
    private const val TS_PACKET_BYTES = 188

    /** What the server said about the URL.  makes a progressive file seekable. */
    data class Result(
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val acceptsRanges: Boolean,
        val fileName: String? = null,
        val verifiedByBytes: Boolean = false,
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

    /** Generic types a correctly-served media file is nonetheless often labeled with: object storage defaults to binary / octet-stream. */
    private val TOLERATED_TYPES = setOf("application/octet-stream", "binary/octet-stream")

    /**
     * Probes [url], or returns null when the request failed outright (DNS, TLS, timeout, 4xx/5xx).
     * Never throws: a failed probe means "the direct path cannot answer for this URL", and the
     * caller decides whether to refuse or fall through to the extractor chain.
     */
    fun probe(url: String, requireBytes: Boolean = false): Result? {
        head(url)?.takeIf { it.contentType != null || it.contentLength != null }?.let { base ->
            // A HEAD carries no body. Sniff when the declared type is not media or too generic to
            // tell a playlist from a file, and whenever the caller wants the container proven
            // rather than asserted.
            if (requireBytes || !base.isMediaType || base.contentType in TOLERATED_TYPES) {
                sniffContentType(url)?.let {
                    return base.copy(contentType = it, verifiedByBytes = true)
                }
            }
            return base
        }

        // The ranged GET both confirms range support and, crucially, already returns the leading
        // bytes — so the magic-byte sniff reads them from here instead of re-fetching the URL
        val (base, body) = rangedGet(url) ?: return null
        magicContentType(body)?.let { return base.copy(contentType = it, verifiedByBytes = true) }
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

    /**
     * Maps a leading byte pattern to a content type for the container families players paste, or
     * null when the bytes are not a container this player can name. Covers every mainstream one,
     * because for a host nobody vouched for this is the only evidence that the link really is
     * media — a `Content-Type` header is just what that host chose to write.
     */
    internal fun magicContentType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun ascii(offset: Int, len: Int) = String(bytes, offset, len, StandardCharsets.US_ASCII)
        fun at(offset: Int, vararg pattern: Int) =
            pattern.withIndex().all { (i, b) -> bytes[offset + i] == b.toByte() }
        return when {
            ascii(4, 4) == "ftyp" -> "video/mp4"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(0, 3) == "FLV" -> "video/x-flv"
            ascii(0, 4) == "OggS" -> "video/ogg"
            ascii(0, 7) == "#EXTM3U" -> "application/vnd.apple.mpegurl"
            at(0, 0x52, 0x49, 0x46, 0x46) && ascii(8, 4) == "AVI " -> "video/x-msvideo"
            at(0, 0x30, 0x26, 0xB2, 0x75) -> "video/x-ms-asf"
            at(0, 0x00, 0x00, 0x01, 0xBA) -> "video/mpeg"
            isTransportStream(bytes) -> "video/mp2t"
            dashManifest(ascii(0, minOf(bytes.size, 64))) -> "application/dash+xml"
            else -> null
        }
    }

    /**
     * True when the bytes carry the MPEG-TS sync byte at the start of two consecutive packets. One
     * `0x47` alone is far too weak — plenty of text starts with a `G`.
     */
    private fun isTransportStream(bytes: ByteArray): Boolean =
        bytes.size > TS_PACKET_BYTES &&
                bytes[0] == 0x47.toByte() &&
                bytes[TS_PACKET_BYTES] == 0x47.toByte()

    /** True when a text head reads as the start of a DASH manifest, with or without an XML prolog. */
    private fun dashManifest(head: String): Boolean {
        val text = head.trimStart('﻿', ' ', '\n', '\r', '\t')
        return text.startsWith("<MPD", ignoreCase = true) ||
                (text.startsWith("<?xml") && head.contains("<MPD", ignoreCase = true))
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
