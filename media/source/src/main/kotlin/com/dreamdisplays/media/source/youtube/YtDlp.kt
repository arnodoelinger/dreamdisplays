package com.dreamdisplays.media.source.youtube

import com.dreamdisplays.api.media.search.model.MediaSearchPage
import com.dreamdisplays.api.media.search.model.MediaSearchResult
import com.dreamdisplays.api.media.search.model.SortOrder
import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.media.source.youtube.binary.YtDlpBinary
import com.dreamdisplays.media.source.youtube.cache.YtFormatCache
import com.dreamdisplays.media.source.youtube.cookie.YtCookieManager
import com.dreamdisplays.media.source.youtube.model.YtStream
import com.dreamdisplays.media.source.youtube.model.YtStreams
import com.dreamdisplays.media.source.youtube.process.YtDlpClientRace
import com.dreamdisplays.media.source.youtube.search.YtDlpSearchCache
import com.dreamdisplays.util.DreamCoroutines
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Has race been abandoned" flag shared between hedge coroutine and abort path.
 */
private class AbandonFlag {
    private val flag = atomic(false)
    val isAbandoned: Boolean get() = flag.value
    fun abandon() {
        flag.value = true
    }
}

/**
 * `yt-dlp` orchestrator: races the in-process [NewPipeResolver] fast path against the `yt-dlp`
 * subprocess (client racing lives in [YtDlpClientRace]), caches the winning result (via
 * [YtFormatCache]), and exposes YouTube search (via [YtDlpSearchCache]).
 */
object YtDlp {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Head start (ms) for `NewPipeExtractor` before `yt-dlp` subprocess (skips spawn if full ladder). */
    private val HEDGE_DELAY_MS: Long =
        System.getProperty("dreamdisplays.ytdlpHedgeMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    /** All `yt-dlp` background work runs on [DreamCoroutines.clientIo] scope. */
    private val cookies = YtCookieManager()

    /** Races `yt-dlp` clients for a single fetch; see [fetchUncached]. */
    private val clientRace = YtDlpClientRace(cookies)

    /** In-memory / disk cache in front of [fetchUncached]. */
    private val formatCache = YtFormatCache(resolve = ::fetchUncached)

    /** Returns stream list for videoUrl (in-memory -> disk -> `yt-dlp`). */
    @Throws(IOException::class)
    fun fetch(videoUrl: String): List<YtStream> {
        if (!MediaUrlPolicy.isAllowed(videoUrl)) {
            return emptyList()
        }
        return formatCache.fetch(videoUrl)
    }

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        DreamCoroutines.clientIo.launch {
            runCatching {
                NewPipeResolver.ensureInitialized()
                NewPipeResolver.prewarmPlayer()
                YtDlpBinary.resolveCommand()
                cookies.prewarm()
            }.onFailure { e ->
                logger.warn("Failed to prewarm yt-dlp.", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        if (!MediaUrlPolicy.isAllowed(videoUrl)) return
        formatCache.prefetch(videoUrl)
    }

    /** Searches YouTube for [query] via InnerTube, returning up to [limit] results; uses a 30-minute in-memory cache. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<MediaSearchResult> = YtDlpSearchCache.search(query, limit)

    /** Fetches up to [limit] related videos for [videoId] via InnerTube; falls back to title search if none found. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> = YtDlpSearchCache.related(videoId, limit)

    /** Fetches the first page (up to [limit] results) matching [query] in [sortOrder]; a fresh network call each time (continuation isn't cacheable). */
    @Throws(IOException::class)
    fun searchPage(query: String, limit: Int, sortOrder: SortOrder = SortOrder.RELEVANCE): MediaSearchPage =
        YtDlpSearchCache.searchPage(query, limit, sortOrder)

    /** Fetches the page following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    @Throws(IOException::class)
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        YtDlpSearchCache.searchMore(continuationToken, limit)

    /** Fetches the first page (up to [limit] results) related to [videoId]. */
    @Throws(IOException::class)
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage = YtDlpSearchCache.relatedPage(videoId, limit)

    /** Fetches the page following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    @Throws(IOException::class)
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        YtDlpSearchCache.relatedMore(continuationToken, limit)

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? = YtDlpSearchCache.extractVideoId(url)

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidateCache(videoUrl: String) = formatCache.invalidate(videoUrl)

    /** Returns a cached YouTube cookie header string for use by HTTP clients other than `yt-dlp`. */
    fun getPublicCookieHeader(): String? = cookies.header()

    /**
     * Resolves streams for [videoUrl]: races the in-process [NewPipeResolver] fast path against the `yt-dlp` subprocess
     * (via [clientRace]) and returns whichever first offers a full quality ladder.
     */
    @Throws(IOException::class)
    private suspend fun fetchUncached(videoUrl: String): List<YtStream> {
        val ytProcesses = CopyOnWriteArrayList<Process>()
        val abandoned = AbandonFlag()
        val ytdlp = DreamCoroutines.clientIo.async {
            if (HEDGE_DELAY_MS > 0) delay(HEDGE_DELAY_MS.milliseconds)
            if (abandoned.isAbandoned) return@async emptyList()
            clientRace.resolve(videoUrl) { proc ->
                ytProcesses.add(proc)
                if (abandoned.isAbandoned) Processes.destroyTree(proc)
            } ?: emptyList()
        }

        // Aborts the parallel yt-dlp branch and reaps any subprocess it managed to spawn
        fun abandonYtDlp() {
            abandoned.abandon()
            ytProcesses.forEach { runCatching { Processes.destroyTree(it) } }
            ytdlp.cancel()
        }

        val viaNewPipe = runCatching { NewPipeResolver.fetch(videoUrl) }
            .onFailure { e ->
                if (e is CancellationException) {
                    abandonYtDlp()
                    throw e
                }
                logger.debug("NewPipe resolve failed for {}: {}.", videoUrl, e.message?.take(200))
            }
            .getOrElse { emptyList() }

        if (YtStreams.usable(viaNewPipe) && YtStreams.offersQualityLadder(viaNewPipe) &&
            viaNewPipe.none { it.isLive }
        ) {
            abandonYtDlp()
            return viaNewPipe
        }
        if (viaNewPipe.isNotEmpty()) {
            logger.debug(
                "NewPipe returned no quality ladder for {} (heights={}); awaiting parallel yt-dlp.",
                videoUrl, YtStreams.distinctHeights(viaNewPipe),
            )
        }

        val viaYtDlp = runCatching {
            withTimeoutOrNull((YtDlpClientRace.FETCH_TIMEOUT_SECONDS + 15).seconds) { ytdlp.await() }
                ?: emptyList<YtStream>().also { abandonYtDlp() }
        }.onFailure { e ->
            abandonYtDlp()
            logger.debug("yt-dlp resolve did not settle for {}: {}.", videoUrl, e.message?.take(200))
        }.getOrElse { emptyList() }

        return when {
            YtStreams.usable(viaYtDlp) && YtStreams.offersQualityLadder(viaYtDlp) -> viaYtDlp
            viaNewPipe.isNotEmpty() -> {
                logger.warn(
                    "yt-dlp only produced a non-ladder fallback-client result for $videoUrl " +
                            "(heights=${YtStreams.distinctHeights(viaYtDlp)}); using NewPipeExtractor's muxed " +
                            "stream instead, since fallback yt-dlp clients are PO-token gated."
                )
                viaNewPipe
            }

            viaYtDlp.isNotEmpty() -> viaYtDlp
            else -> throw IOException("All yt-dlp clients failed for $videoUrl.")
        }
    }
}
