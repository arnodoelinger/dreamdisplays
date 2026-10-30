package com.dreamdisplays.media.player.process

import com.dreamdisplays.shared.policy.MediaHosts
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Puts googlevideo VOD URLs and Google Drive downloads that an `FFmpeg` process reads behind the
 * loopback [RangeProxyServer].
 */
internal object GoogleVideoRangeProxy {
    private const val GOOGLEVIDEO_SUFFIX = ".googlevideo.com"
    private const val DRIVE_HOST = "drive.usercontent.google.com"
    private const val MAX_REDIRECTS = 3

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }

    private val server: RangeProxyServer by lazy { RangeProxyServer(::cachedFetch) }

    private val chunks = object : LinkedHashMap<String, UpstreamChunk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UpstreamChunk>?) = size > MAX_CACHED_CHUNKS
    }
    private const val MAX_CACHED_CHUNKS = 24
    private const val CACHED_HEAD_BYTES = 1L shl 20
    private const val CACHED_TAIL_BYTES = 32L shl 20

    fun wrap(url: String): String {
        if (!eligible(url)) return url
        return server.register(url, contentLength(url)) ?: url
    }

    fun needsProxy(url: String): Boolean =
        runCatching { URI(url) }.getOrNull()?.let(::isDrive) == true

    internal fun eligible(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (isDrive(uri)) return true
        if (uri.host?.lowercase()?.endsWith(GOOGLEVIDEO_SUFFIX) != true) return false
        if (uri.rawPath != "/videoplayback") return false
        return contentLength(url) != null
    }

    private fun isDrive(uri: URI): Boolean =
        uri.host.equals(DRIVE_HOST, ignoreCase = true) && uri.rawPath == "/download"

    private fun sameFamily(origin: URI, host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return if (isDrive(origin)) {
            h == DRIVE_HOST || h.endsWith(".googleusercontent.com")
        } else {
            h.endsWith(GOOGLEVIDEO_SUFFIX)
        }
    }

    internal fun contentLength(url: String): Long? =
        runCatching { URI(url).rawQuery }.getOrNull()
            ?.split('&')
            ?.firstOrNull { it.startsWith("clen=") }
            ?.substring(5)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }

    private fun cachedFetch(url: String, from: Long, to: Long): UpstreamChunk {
        val key = "$from-$to $url"
        synchronized(chunks) { chunks[key] }?.let { return it }
        val chunk = fetch(url, from, to)
        val total = chunk.total
        val reusable = from < CACHED_HEAD_BYTES || (total != null && from >= total - CACHED_TAIL_BYTES)
        if (chunk.status == 206 && reusable) synchronized(chunks) { chunks[key] = chunk }
        return chunk
    }

    private fun fetch(url: String, from: Long, to: Long): UpstreamChunk {
        val origin = URI(url)
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val request = HttpRequest.newBuilder(URI(target))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Range", "bytes=$from-$to")
                .header("User-Agent", MediaProcess.USER_AGENT)
                .apply { MediaHosts.refererFor(target)?.let { header("Referer", it) } }
                .GET()
                .build()
            val response = try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
            val status = response.statusCode()
            if (status in 300..399) {
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw IOException("Redirect without a location.")
                val next = URI(target).resolve(location).toString()
                if (!next.startsWith("https://", ignoreCase = true) ||
                    !sameFamily(origin, URI(next).host)
                ) throw IOException("Refusing redirect off-platform.")
                target = next
                return@repeat
            }
            val total = response.headers().firstValue("content-range").orElse(null)
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
            val contentType = response.headers().firstValue("content-type").orElse(null)
            return UpstreamChunk(status, total, contentType, if (status == 206) response.body() else ByteArray(0))
        }
        throw IOException("Too many redirects.")
    }
}
