package com.dreamdisplays.media.source.ytdlp

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import com.dreamdisplays.api.media.source.MediaMetadata
import com.dreamdisplays.api.media.source.MediaResolver
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.api.media.source.ResolvedMedia
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.util.net.DreamHttpClient
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * How long a resolved video is reused before `NewPipeExtractor` is hit again. Matches
     * [FormatDiskCache.DEFAULT_TTL_MS] — [YtDlp] trusts the same googlevideo URLs for that long, so
     * there is no reason for this fast-path cache to expire them sooner and force a redundant
     * re-extraction (and, via [YtDlp.fetchUncached], a wasted parallel `yt-dlp` race) on every replay.
     */
    private val POSITIVE_TTL_NANOS = FormatDiskCache.DEFAULT_TTL_MS * 1_000_000L

    /**
     * Partial ("walled") resolutions: reused across replays within a viewing session but rechecked
     * periodically in case YouTube's PO-token/SABR wall lifts for this video. Matches
     * [FormatDiskCache.PARTIAL_TTL_MS].
     */
    private val PARTIAL_TTL_NANOS = FormatDiskCache.PARTIAL_TTL_MS * 1_000_000L

    /** Live playlist URLs carry short-lived tokens, so reuse is capped much lower than VOD. */
    private val LIVE_TTL_NANOS = 60_000_000_000L

    private val NEGATIVE_TTL_NANOS = 20_000_000_000L
    private const val MAX_CACHE_ENTRIES = 256

    /** Recently resolved videos, keyed by video id (falling back to the full URL). */
    private val cache: Cache<String, CacheEntry> = Caffeine.newBuilder()
        .maximumSize(MAX_CACHE_ENTRIES.toLong())
        .expireAfter(object : Expiry<String, CacheEntry> {
            override fun expireAfterCreate(key: String, value: CacheEntry, currentTime: Long): Long =
                value.ttlNanos

            override fun expireAfterUpdate(
                key: String,
                value: CacheEntry,
                currentTime: Long,
                currentDuration: Long,
            ): Long = value.ttlNanos

            override fun expireAfterRead(
                key: String,
                value: CacheEntry,
                currentTime: Long,
                currentDuration: Long,
            ): Long = currentDuration
        })
        .build()

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
     * the result. A `null` result (`NewPipeExtractor` declined or failed) is cached too, with a shorter
     * TTL, so the immediate [resolve] -> [fetch] retry for the same video does not pay a second network
     * round. The TTL otherwise depends on what kind of result it is — see [POSITIVE_TTL_NANOS] and
     * [PARTIAL_TTL_NANOS].
     */
    private fun resolveCached(url: String): Resolved? {
        val key = YouTubeUrls.extractVideoId(url) ?: url
        return cache.get(key) {
            val resolved = doExtract(url)
            val ttl = when {
                resolved == null -> NEGATIVE_TTL_NANOS
                resolved.isLive -> LIVE_TTL_NANOS
                YtStreams.offersQualityLadder(resolved.streams) -> POSITIVE_TTL_NANOS
                else -> PARTIAL_TTL_NANOS
            }
            CacheEntry(value = resolved, ttlNanos = ttl)
        }.value
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

    /** A cached [Resolved] (or `null` for a known-unresolvable video) with its `Caffeine` TTL. */
    private class CacheEntry(val value: Resolved?, val ttlNanos: Long)

    /**
     * Minimal [Downloader] implementation over the shared facade, honoring the configured proxy
     * (same handling as [YouTubeInnerTube]).
     */
    private object YtHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val data = request.dataToSend()
            val response = DreamHttpClient.execute(
                request.url(),
                DreamHttpClient.RequestOptions(
                    method = request.httpMethod(),
                    headers = request.headers(),
                    body = data,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 15_000,
                    proxyUrl = ResolverConfig.ytdlpProxy,
                ),
            )
            return Response(
                response.code,
                response.message,
                response.headers,
                response.body.toString(StandardCharsets.UTF_8),
                response.finalUrl,
            )
        }
    }
}
