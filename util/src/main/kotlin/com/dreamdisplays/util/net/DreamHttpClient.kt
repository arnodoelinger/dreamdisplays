package com.dreamdisplays.util.net

import kotlinx.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Shared blocking HTTP facade for bootstrap downloads, GitHub API requests, thumbnail fetches,
 * and YouTube resolver traffic. Keeps `OkHttp`-specific types out of callers so the dependency
 * remains an implementation detail of `util`.
 */
object DreamHttpClient {
    private val logger = LoggerFactory.getLogger("DreamDisplays/Http")

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000L

    private val baseClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    data class RequestOptions(
        val method: String = "GET",
        val headers: Map<String, List<String>> = emptyMap(),
        val body: ByteArray? = null,
        val contentType: String? = null,
        val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        val writeTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        val callTimeoutMs: Long = 0L,
        val followRedirects: Boolean = true,
        val proxyUrl: String? = null,
    )

    data class HttpResponse(
        val code: Int,
        val message: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val finalUrl: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299

        fun bodyString(charset: Charset = StandardCharsets.UTF_8): String = body.toString(charset)
    }

    fun headersOf(vararg headers: Pair<String, String>): Map<String, List<String>> =
        headers.groupBy({ it.first }, { it.second })

    @Throws(IOException::class)
    fun execute(url: String, options: RequestOptions = RequestOptions()): HttpResponse {
        val request = request(url, options)
        clientFor(options).newCall(request).execute().use { response ->
            return HttpResponse(
                code = response.code,
                message = response.message,
                headers = response.headers.toMultimap(),
                body = response.readBodyBytes(),
                finalUrl = response.request.url.toString(),
            )
        }
    }

    @Throws(IOException::class)
    fun readText(url: String, options: RequestOptions = RequestOptions()): String =
        readBytes(url, options).toString(StandardCharsets.UTF_8)

    @Throws(IOException::class)
    fun readBytes(url: String, options: RequestOptions = RequestOptions()): ByteArray {
        val response = execute(url, options)
        if (!response.isSuccessful) throw httpException(response, url)
        return response.body
    }

    @Throws(IOException::class)
    fun downloadToFile(
        url: String,
        dest: Path,
        options: RequestOptions = RequestOptions(),
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): Long {
        val request = request(url, options.copy(method = "GET", body = null, contentType = null))
        clientFor(options).newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw httpException(
                    HttpResponse(
                        code = response.code,
                        message = response.message,
                        headers = response.headers.toMultimap(),
                        body = response.readBodyBytes(),
                        finalUrl = response.request.url.toString(),
                    ),
                    url,
                )
            }
            val responseBody = response.body
            val total = responseBody.contentLength()
            var downloaded = 0L
            dest.parent?.let(Files::createDirectories)
            response.bodyStream().use { input ->
                Files.newOutputStream(
                    dest,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        out.write(buffer, 0, read)
                        downloaded += read.toLong()
                        onProgress?.invoke(downloaded, total)
                        read = input.read(buffer)
                    }
                }
            }
            return downloaded
        }
    }

    @Throws(IOException::class)
    fun peekRedirectLocation(url: String, options: RequestOptions = RequestOptions()): String? {
        val noRedirects = options.copy(followRedirects = false)
        val request = request(url, noRedirects)
        clientFor(noRedirects).newCall(request).execute().use { response ->
            return if (response.code in 300..399) response.header("Location") else null
        }
    }

    private fun request(url: String, options: RequestOptions): Request {
        val builder = Request.Builder().url(url)
        for ((name, values) in options.headers) {
            for (value in values) builder.addHeader(name, value)
        }
        val method = options.method.uppercase(Locale.ROOT)
        builder.method(method, requestBody(method, options))
        return builder.build()
    }

    private fun requestBody(method: String, options: RequestOptions): RequestBody? {
        if (method == "GET" || method == "HEAD") return null
        val mediaType = options.contentType?.toMediaTypeOrNull()
        return (options.body ?: ByteArray(0)).toRequestBody(mediaType)
    }

    private fun clientFor(options: RequestOptions): OkHttpClient {
        val builder = baseClient.newBuilder()
            .followRedirects(options.followRedirects)
            .followSslRedirects(options.followRedirects)
            .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(options.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(options.writeTimeoutMs, TimeUnit.MILLISECONDS)
        if (options.callTimeoutMs > 0L) builder.callTimeout(options.callTimeoutMs, TimeUnit.MILLISECONDS)
        proxyFor(options.proxyUrl)?.let(builder::proxy)
        return builder.build()
    }

    private fun proxyFor(rawProxyUrl: String?): Proxy? {
        val raw = rawProxyUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val uri = try {
            URI.create(raw)
        } catch (e: Exception) {
            logger.warn("Invalid proxy URL: $raw.")
            return null
        }
        val type = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        val host = uri.host ?: run {
            logger.warn("Invalid proxy URL without host: $raw.")
            return null
        }
        val port = if (uri.port > 0) uri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
        return Proxy(type, InetSocketAddress(host, port))
    }

    private fun Response.readBodyBytes(): ByteArray {
        return bodyStream().use { it.readBytes() }
    }

    private fun Response.bodyStream() =
        if (header("Content-Encoding")?.equals("gzip", ignoreCase = true) == true) {
            GZIPInputStream(body.byteStream())
        } else {
            body.byteStream()
        }

    private fun httpException(response: HttpResponse, url: String): IOException {
        val snippet = response.bodyString().take(500)
        val suffix = if (snippet.isBlank()) "" else ": $snippet"
        return IOException("HTTP ${response.code} for $url$suffix")
    }
}
