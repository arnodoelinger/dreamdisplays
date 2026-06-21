package com.dreamdisplays.ytdlp

import com.dreamdisplays.media.api.MediaMetadata
import com.dreamdisplays.media.api.MediaResolver
import com.dreamdisplays.media.api.MediaSource
import com.dreamdisplays.media.api.ResolvedMedia
import com.dreamdisplays.media.api.YouTubeUrls
import kotlin.time.Duration.Companion.nanoseconds
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

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

    /** How long a resolved (or unresolvable) video is reused before `NewPipeExtractor` is hit again. */
    private val POSITIVE_TTL_NANOS = 60L * 1_000_000_000L
    private val NEGATIVE_TTL_NANOS = 20L * 1_000_000_000L
    private const val MAX_CACHE_ENTRIES = 256

    /** Recently resolved videos, keyed by video id (falling back to the full URL). */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override val priority: Int = 10

    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.YouTube

    override fun resolve(source: MediaSource): ResolvedMedia {
        ensureInitialized()
        check(initialized.get()) { "NewPipeExtractor failed to initialize" }
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("Twitch not supported by NewPipeResolver")
        val resolved = resolveCached(url)
            ?: throw IllegalStateException("NewPipe could not resolve $url; deferring to yt-dlp")
        // YouTube often exposes only the muxed 360p track to this client (adaptive tracks are
        // SABR / DASH-only); failing here lets the resolver chain fall through to yt-dlp, which
        // still gets the full quality ladder.
        if (!resolved.isLive && !YtStreams.offersQualityLadder(resolved.streams)) {
            val heights = YtStreams.distinctHeights(resolved.streams)
            throw IllegalStateException("NewPipe returned no quality ladder (heights=$heights); deferring to yt-dlp")
        }
        return ResolvedMedia(
            streams = resolved.streams.map { it.toMediaStream() },
            metadata = MediaMetadata(
                title = resolved.title,
                uploader = resolved.uploader,
                duration = resolved.durationNanos.takeIf { it > 0L }?.nanoseconds,
                thumbnailUrl = resolved.thumbnailUrl,
                viewCount = resolved.viewCount,
                likeCount = resolved.likeCount,
                uploadDate = null,
            ),
            isLive = resolved.isLive,
            isSeekable = resolved.isSeekable,
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
     * Fetches and compiles YouTube's base JavaScript player ahead of the first real resolution.
     * This moves the one-time costs off the playback critical path: the `base.js` download and the
     * Rhino parse of both the signature-timestamp and n-parameter deobfuscation functions, all of
     * which `NewPipeExtractor` caches globally once warmed. Best-effort; failures are ignored.
     */
    fun prewarmPlayer() {
        if (!initialized.get()) return
        try {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp("")
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                "",
                "https://dummy.googlevideo.com/videoplayback?n=0000000000000000",
            )
        } catch (e: Throwable) {
            logger.debug("NewPipe player prewarm skipped: {}", e.message)
        }
    }

    /**
     * Resolves the playable streams for [videoUrl] via NewPipeExtractor, mapped to [YtStream].
     * Returns an empty list on any failure (caller falls back to `yt-dlp`).
     */
    fun fetch(videoUrl: String): List<YtStream> {
        ensureInitialized()
        if (!initialized.get()) return emptyList()
        return resolveCached(videoUrl)?.streams ?: emptyList()
    }

    /**
     * Returns the cached resolution for [url] if still fresh, otherwise resolves it once and caches
     * the result. A `null` result (`NewPipeExtractor` declined or failed) is cached too, with a shorter TTL, so
     * the immediate [resolve] -> [fetch] retry for the same video does not pay a second network round.
     */
    private fun resolveCached(url: String): Resolved? {
        val key = YouTubeUrls.extractVideoId(url) ?: url
        val now = System.nanoTime()
        cache[key]?.let { if (now < it.expiresAtNanos) return it.value }

        val resolved = doExtract(url)
        val ttl = if (resolved != null) POSITIVE_TTL_NANOS else NEGATIVE_TTL_NANOS
        pruneIfNeeded(now)
        cache[key] = CacheEntry(resolved, now + ttl)
        return resolved
    }

    /** Drops expired entries (and, if still over the cap, clears the cache) before inserting a new one. */
    private fun pruneIfNeeded(now: Long) {
        if (cache.size < MAX_CACHE_ENTRIES) return
        cache.entries.removeIf { now >= it.value.expiresAtNanos }
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
    }

    /**
     * Drives the [StreamExtractor] directly for [url]: a single [StreamExtractor.fetchPage] followed
     * by reads of only the stream lists and the handful of metadata fields we map. Each metadata read
     * is guarded individually so one bad field never sinks the whole resolution. Returns `null` when
     * no usable stream could be extracted.
     */
    private fun doExtract(url: String): Resolved? {
        return try {
            val service = NewPipe.getServiceByUrl(url)
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            val streamType = safe { extractor.streamType } ?: StreamType.VIDEO_STREAM
            val live = streamType == StreamType.LIVE_STREAM ||
                    streamType == StreamType.AUDIO_LIVE_STREAM ||
                    streamType == StreamType.POST_LIVE_STREAM
            val durationNanos = Durations.secondsToNanos(safe { extractor.length } ?: 0L)
            val seekable = !live && durationNanos > 0L

            val streams = mapStreams(extractor, live, seekable, durationNanos)
            if (streams.isEmpty()) return null

            Resolved(
                streams = streams,
                title = safe { extractor.name }?.takeIf { it.isNotBlank() },
                uploader = safe { extractor.uploaderName }?.takeIf { it.isNotBlank() },
                durationNanos = durationNanos,
                thumbnailUrl = safe { extractor.thumbnails.firstOrNull()?.url },
                viewCount = safe { extractor.viewCount }?.takeIf { it > 0L },
                likeCount = safe { extractor.likeCount }?.takeIf { it > 0L },
                isLive = live,
                isSeekable = seekable,
            )
        } catch (e: Throwable) {
            logger.debug("NewPipeExtractor fetch failed for {}: {}", url, e.message)
            null
        }
    }

    /** Maps the directly-fetched [extractor]'s stream lists into the flat [YtStream] list the player pipeline expects. */
    private fun mapStreams(
        extractor: StreamExtractor,
        live: Boolean,
        seekable: Boolean,
        durationNanos: Long,
    ): List<YtStream> {
        val out = ArrayList<YtStream>()
        // Muxed progressive streams (video + audio in one URL)
        for (s in safe { extractor.videoStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = true, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive video-only streams
        for (s in safe { extractor.videoOnlyStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(videoToYt(s, hasAudio = false, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        // Adaptive audio-only streams
        for (s in safe { extractor.audioStreams }.orEmpty()) {
            if (!acceptable(s)) continue
            out.add(audioToYt(s, live = live, seekable = seekable, durationNanos = durationNanos))
        }
        return out
    }

    /** Runs [block], swallowing any extractor failure and returning null so optional fields degrade gracefully. */
    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    /** Converts a `NewPipeExtractor` [VideoStream] to a [YtStream]. */
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

    /** Fully resolved video, cached and shared between the [resolve] and [fetch] entry points. */
    private class Resolved(
        val streams: List<YtStream>,
        val title: String?,
        val uploader: String?,
        val durationNanos: Long,
        val thumbnailUrl: String?,
        val viewCount: Long?,
        val likeCount: Long?,
        val isLive: Boolean,
        val isSeekable: Boolean,
    )

    /** A cached [Resolved] (or `null` for a known-unresolvable video) with its monotonic expiry. */
    private class CacheEntry(val value: Resolved?, val expiresAtNanos: Long)

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
        private fun openConnection(urlStr: String): HttpURLConnection = ProxyConnections.open(urlStr)
    }
}
