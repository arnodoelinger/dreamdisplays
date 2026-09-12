package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.source.url.YouTubeUrls
import com.dreamdisplays.api.media.source.model.MediaMetadata
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.media.source.youtube.cache.FormatDiskCache
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.media.source.youtube.newpipe.NewPipeLadderTracker
import com.dreamdisplays.media.source.youtube.newpipe.NewPipeResolved
import com.dreamdisplays.media.source.youtube.newpipe.NewPipeStreamExtraction
import com.dreamdisplays.media.source.youtube.newpipe.YtHttpDownloader
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.net.DreamHttpClient
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.nanoseconds

/**
 * In-process YouTube stream resolver backed by `NewPipeExtractor`; fast path before `yt-dlp`
 * fallback. Extraction lives in [NewPipeStreamExtraction], the overlap heuristic in
 * [NewPipeLadderTracker]; this class owns the resolve cache and the [MediaResolverService] contract that
 * ties them together.
 */
object NewPipeResolver : MediaResolverService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Whether `NewPipeExtractor` has been initialized with our HTTP downloader. */
    private val initialized = atomic(false)

    /** How long a resolved video is reused before `NewPipeExtractor` is hit again. Matches FormatDiskCache.DEFAULT_TTL_MS. */
    private const val POSITIVE_TTL_NANOS = FormatDiskCache.DEFAULT_TTL_MS * 1_000_000L

    /**
     * Partial ("walled") resolutions: reused across replays within a viewing session but rechecked
     * periodically in case YouTube's PO-token / SABR wall lifts for this video. Matches
     * [FormatDiskCache.PARTIAL_TTL_MS].
     */
    private const val PARTIAL_TTL_NANOS = FormatDiskCache.PARTIAL_TTL_MS * 1_000_000L

    /** Live playlist URLs carry short-lived tokens, so reuse is capped much lower than VOD. */
    private const val LIVE_TTL_NANOS = 60_000_000_000L

    /** Negative cache for videos that `NewPipeExtractor` cannot resolve (e.g. age-gated, region-blocked, or deleted). */
    private const val NEGATIVE_TTL_NANOS = 20_000_000_000L

    /** Maximum number of entries in the in-memory cache. */
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

    /** Priority in the resolver chain: `NewPipeExtractor` is fast and in-process, so it goes first. */
    override val priority: Int = 10

    /** True if this resolver can handle [source], which is only YouTube. */
    override fun canResolve(source: MediaSource): Boolean = source is MediaSource.YouTube

    /** Resolves [source] via `NewPipeExtractor`, falling back to `yt-dlp` if it fails or returns no quality ladder. */
    override fun resolve(source: MediaSource): ResolvedMedia {
        ensureInitialized()
        check(initialized.value) { "NewPipeExtractor failed to initialize" }
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("Twitch not supported by NewPipeResolver.")
        val resolved = resolveCached(url, overlapFallback = true)
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
            subtitles = resolved.subtitles,
        )
    }

    /** Drops [url]'s cached resolution so the next resolve goes back to YouTube. */
    fun invalidate(url: String) {
        cache.invalidate(YouTubeUrls.extractVideoId(url) ?: url)
    }

    /** Initializes `NewPipeExtractor` with our HTTP downloader exactly once. Safe to call repeatedly. */
    fun ensureInitialized() {
        if (!initialized.compareAndSet(expect = false, update = true)) return
        runCatching {
            NewPipe.init(YtHttpDownloader)
        }.onFailure { e ->
            initialized.value = false
            logger.warn("NewPipe init failed: ${e.message}.")
        }
    }

    fun warmConnection() {
        DreamCoroutines.clientIo.launch {
            runCatching {
                DreamHttpClient.executeLimited(
                    "https://www.youtube.com/",
                    maxBytes = 1,
                    DreamHttpClient.RequestOptions(method = "HEAD", connectTimeoutMs = 5_000, readTimeoutMs = 5_000),
                )
            }.onFailure { e ->
                logger.debug("Connection warmup to youtube.com failed: {}", e.message)
            }
        }
    }

    /** Fetches and compiles YouTube's base JavaScript player ahead of first resolution. */
    fun prewarmPlayer() {
        if (!initialized.value) return
        runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp("")
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                "",
                "https://dummy.googlevideo.com/videoplayback?n=0000000000000000",
            )
        }.onFailure { e ->
            logger.debug("NewPipe player prewarm skipped: {}", e.message)
        }
    }

    /**
     * Resolves the playable streams for [videoUrl] via `NewPipeExtractor`, mapped to [YtStream].
     * Returns an empty list on any failure (caller falls back to `yt-dlp`).
     */
    fun fetch(videoUrl: String): List<YtStream> {
        ensureInitialized()
        if (!initialized.value) return emptyList()
        // No overlap here: this is the call [YtDlp.fetchUncached] makes from inside the very race
        // the overlap exists to start.
        return resolveCached(videoUrl)?.streams ?: emptyList()
    }

    /**
     * Warms this resolver (and, when the wall is likely, the `yt-dlp` fallback alongside it) for
     * [source]. Reports false for a walled or failed extraction, so the registry goes on to warm the
     * fallback that is about to be needed.
     */
    override fun prefetch(source: MediaSource): Boolean {
        if (!canResolve(source)) return false
        ensureInitialized()
        if (!initialized.value) return false
        val url = source.toResolvableUrl() ?: return false
        val resolved = runCatching { resolveCached(url, overlapFallback = true) }.getOrNull() ?: return false
        return resolved.isLive || YtStreams.offersQualityLadder(resolved.streams)
    }

    /** Returns cached resolution if fresh, otherwise resolves, caches, and records whether quality ladder is available. */
    private fun resolveCached(url: String, overlapFallback: Boolean = false): NewPipeResolved? {
        val key = YouTubeUrls.extractVideoId(url) ?: url
        cache.getIfPresent(key)?.let { return it.value }
        if (overlapFallback && NewPipeLadderTracker.shouldOverlapFallback()) {
            runCatching { YtDlp.prefetchFormats(url) }
        }
        return cache.get(key) {
            val resolved = NewPipeStreamExtraction.extract(url)
            val laddered = resolved != null && YtStreams.offersQualityLadder(resolved.streams)
            val ttl = when {
                resolved == null -> NEGATIVE_TTL_NANOS
                resolved.isLive -> LIVE_TTL_NANOS
                laddered -> POSITIVE_TTL_NANOS
                else -> PARTIAL_TTL_NANOS
            }
            if (resolved == null || !resolved.isLive) NewPipeLadderTracker.recordLadderOutcome(laddered)
            CacheEntry(value = resolved, ttlNanos = ttl)
        }.value
    }

    /** A cached [NewPipeResolved] (or `null` for a known-unresolvable video) with its `Caffeine` TTL. */
    private class CacheEntry(val value: NewPipeResolved?, val ttlNanos: Long)
}
