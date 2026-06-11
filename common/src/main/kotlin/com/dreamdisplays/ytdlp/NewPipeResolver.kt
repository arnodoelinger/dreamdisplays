package com.dreamdisplays.ytdlp

import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.media.api.MediaMetadata
import com.dreamdisplays.media.api.MediaResolver
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.media.api.ResolvedMedia
import kotlin.time.Duration.Companion.nanoseconds
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.round

/**
 * In-process YouTube stream resolver backed by NewPipeExtractor. This is the fast path used before
 * falling back to the `yt-dlp` subprocess: it resolves de-throttled (n-parameter solved), FFmpeg-ready
 * stream URLs over plain HTTP with no process spawn, no Python start-up, and no browser cookies.
 *
 * It never throws to its callers; on any failure it returns an empty list so [YtDlp.fetchUncached]
 * transparently falls back to `yt-dlp`.
 */
object NewPipeResolver : MediaResolver {
    private val logger = LoggerFactory.getLogger("DreamDisplays/NewPipe")

    private val initialized = AtomicBoolean(false)

    override val priority: Int = 10

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.YouTube

    override fun resolve(source: MediaSource): ResolvedMedia {
        ensureInitialized()
        check(initialized.get()) { "NewPipeExtractor failed to initialize" }
        val url = when (source) {
            is MediaSource.YouTube -> "https://www.youtube.com/watch?v=${source.videoId}"
            is MediaSource.Remote -> source.url
            is MediaSource.DirectStream -> source.streamUrl
            is MediaSource.Twitch -> throw UnsupportedOperationException("Twitch not supported by NewPipeResolver")
        }
        val info = StreamInfo.getInfo(url)
        val isLive = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM ||
                info.streamType == StreamType.POST_LIVE_STREAM
        val metadata = MediaMetadata(
            title = info.name.ifBlank { null },
            uploader = info.uploaderName.ifBlank { null },
            duration = if (info.duration > 0) durationToNanos(info.duration).nanoseconds else null,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            viewCount = info.viewCount.takeIf { it > 0 },
            likeCount = info.likeCount.takeIf { it > 0 },
            uploadDate = null,
        )
        return ResolvedMedia(
            streams = mapStreams(info).map { it.toMediaStream() },
            metadata = metadata,
            isLive = isLive,
            isSeekable = !isLive && info.duration > 0,
        )
    }

    /** Initializes NewPipeExtractor with our HTTP downloader exactly once. Safe to call repeatedly. */
    fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            NewPipe.init(YtHttpDownloader)
        } catch (e: Throwable) {
            initialized.set(false)
            logger.warn("NewPipe init failed: ${e.message}")
        }
    }

    /**
     * Resolves the playable streams for [videoUrl] via NewPipeExtractor, mapped to [YtStream].
     * Returns an empty list on any failure (caller falls back to `yt-dlp`).
     */
    fun fetch(videoUrl: String): List<YtStream> {
        ensureInitialized()
        if (!initialized.get()) return emptyList()
        return try {
            mapStreams(StreamInfo.getInfo(videoUrl))
        } catch (e: Throwable) {
            logger.debug("NewPipe fetch failed for {}: {}", videoUrl, e.message)
            emptyList()
        }
    }

    /** Maps a NewPipeExtractor [StreamInfo] into the flat [YtStream] list the player pipeline expects. */
    private fun mapStreams(info: StreamInfo): List<YtStream> {
        val live = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM ||
                info.streamType == StreamType.POST_LIVE_STREAM
        val durationNanos = durationToNanos(info.duration)
        val seekable = !live && durationNanos > 0L

        val out = ArrayList<YtStream>()
        // Muxed progressive streams (video + audio in one URL)
        for (s in info.videoStreams) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = true, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive video-only streams
        for (s in info.videoOnlyStreams) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = false, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive audio-only streams
        for (s in info.audioStreams) {
            if (!acceptable(s)) continue
            out.add(audioToYt(s, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        return out
    }

    /** Converts a NewPipeExtractor [VideoStream] to a [YtStream]. */
    @Suppress("DEPRECATION")
    private fun videoToYt(
        s: VideoStream,
        hasAudio: Boolean,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): YtStream {
        val ext = s.format?.suffix
        val mime = s.format?.mimeType ?: "video/${ext ?: "mp4"}"
        return YtStream(
            s.content,
            mime,
            ext,
            protocolOf(s),
            s.resolution.ifBlank { null },
            s.width.takeIf { it > 0 },
            s.height.takeIf { it > 0 },
            null,
            null,
            s.codec.ifBlank { null },
            null,
            s.fps.takeIf { it > 0 }?.toDouble(),
            s.bitrate.takeIf { it > 0 }?.let { it / 1000.0 },
            true,
            hasAudio,
            live,
            seekable,
            durationNanos,
        )
    }

    /** Converts a NewPipeExtractor [AudioStream] to a [YtStream]. */
    private fun audioToYt(
        s: AudioStream,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): YtStream {
        val ext = s.format?.suffix
        val mime = s.format?.mimeType ?: "audio/${ext ?: "mp4"}"
        return YtStream(
            url = s.content,
            mimeType = mime,
            container = ext,
            protocol = protocolOf(s),
            resolution = null,
            width = null,
            height = null,
            audioTrackId = s.audioTrackId,
            audioTrackName = s.audioTrackName,
            vcodec = null,
            acodec = s.codec.ifBlank { null },
            fps = null,
            tbrKbps = s.averageBitrate.takeIf { it > 0 }?.toDouble(),
            hasVideo = false,
            hasAudio = true,
            isLive = live,
            isSeekable = seekable,
            durationNanos = durationNanos,
        )
    }

    /** True if the stream is a directly playable HTTP or HLS URL (FFmpeg can consume those as `-i`). */
    private fun acceptable(s: Stream): Boolean =
        s.isUrl && s.content.isNotBlank() &&
                (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || s.deliveryMethod == DeliveryMethod.HLS)

    /** Maps the NewPipeExtractor delivery method to the protocol label used by [YtStream]. */
    private fun protocolOf(s: Stream): String =
        if (s.deliveryMethod == DeliveryMethod.HLS) "m3u8_native" else "https"

    /** Converts a duration in seconds to nanoseconds, clamped to [Long.MAX_VALUE]; returns 0 for non-positive. */
    private fun durationToNanos(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        val nanos = seconds.toDouble() * 1_000_000_000.0
        if (nanos >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return max(0L, round(nanos).toLong())
    }

    /**
     * Minimal [Downloader] implementation over [HttpURLConnection], honoring the configured proxy
     * (same handling as [YouTubeInnerTube]) and transparently decompressing gzip responses.
     */
    private object YtHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = openConnection(request.url())
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            for ((name, values) in request.headers()) {
                for (value in values) conn.addRequestProperty(name, value)
            }
            val data = request.dataToSend()
            if (data != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            val body = readBody(conn, code)
            return Response(code, conn.responseMessage, conn.headerFields, body, conn.url.toString())
        }

        /** Reads the response (or error) body, decompressing it if the server sent gzip. */
        private fun readBody(conn: HttpURLConnection, code: Int): String {
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: return ""
            val stream = if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        }

        /** Opens a connection for [urlStr], routing through the configured proxy if one is set. */
        private fun openConnection(urlStr: String): HttpURLConnection {
            val uri = URI.create(urlStr)
            val proxyStr = try {
                ClientStateManager.config.ytdlpProxy.trim()
            } catch (_: Exception) {
                ""
            }
            if (proxyStr.isEmpty()) return uri.toURL().openConnection() as HttpURLConnection
            val proxyUri = try {
                URI.create(proxyStr)
            } catch (_: Exception) {
                return uri.toURL().openConnection() as HttpURLConnection
            }
            val type = when (proxyUri.scheme?.lowercase()) {
                "socks5", "socks4", "socks" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            val port = if (proxyUri.port > 0) proxyUri.port else if (type == Proxy.Type.SOCKS) 1080 else 8080
            val host = proxyUri.host ?: return uri.toURL().openConnection() as HttpURLConnection
            return uri.toURL().openConnection(Proxy(type, InetSocketAddress(host, port))) as HttpURLConnection
        }
    }
}
