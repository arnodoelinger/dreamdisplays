package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.api.media.search.MediaSearchPage
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.security.MediaUrlPolicy
import com.dreamdisplays.media.runtime.Processes
import com.dreamdisplays.media.source.ytdlp.YtDlp.FALLBACK_CLIENTS
import com.dreamdisplays.media.source.ytdlp.YtDlp.HEDGE_DELAY_MS
import com.dreamdisplays.media.source.ytdlp.YtDlp.PRIMARY_CLIENT
import com.dreamdisplays.media.source.ytdlp.YtDlp.bestResult
import com.dreamdisplays.media.source.ytdlp.YtDlp.formatMemo
import com.dreamdisplays.media.source.ytdlp.YtDlp.offersFullResult
import com.dreamdisplays.media.source.ytdlp.YtDlp.raceClients
import com.dreamdisplays.util.AsyncMemo
import com.dreamdisplays.util.DreamCoroutines
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One-shot "has the race been abandoned" flag shared between [YtDlp.fetchUncached]'s hedge coroutine
 * and its abort path.
 */
private class AbandonFlag {
    private val flag = atomic(false)
    val isAbandoned: Boolean get() = flag.value
    fun abandon() {
        flag.value = true
    }
}

/**
 * Same rationale as [AbandonFlag]: a per-race countdown that must survive capture across the
 * parallel client coroutines in [YtDlp.raceParallel].
 */
private class RemainingCountdown(initial: Int) {
    private val remaining = atomic(initial)
    fun decrementAndGet(): Int = remaining.decrementAndGet()
}

/**
 * `yt-dlp` orchestrator: caching and request deduplication around the NewPipe fast path and the
 * `yt-dlp` subprocess. Binary provisioning lives in [YtDlpBinary], cookies in [YtCookieManager],
 * and `-J` output parsing in [YtDlpOutputParser].
 */
object YtDlp {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")
    private const val CACHE_TTL_MS: Long = FormatDiskCache.DEFAULT_TTL_MS
    private const val INFO_CACHE_TTL_MS: Long = 30L * 60L * 1_000L

    /** Per-invocation yt-dlp wait. With cookies off + token-free clients a warm fetch is sub-second,
     *  so this only bounds genuine failures; keep it short so they surface fast. */
    private const val FETCH_TIMEOUT_SECONDS: Long = 25L

    /**
     * The client tried first, alone: in practice it is the only one YouTube still serves a full
     * downloadable adaptive ladder to, so a single fast request usually settles resolution.
     */
    private const val PRIMARY_CLIENT = "android_vr"

    /**
     * Token-free, non-DRM clients raced (one subprocess each) in parallel only when [PRIMARY_CLIENT]
     * yields nothing usable — these currently hit the PO-token / SABR wall ("requested format is not
     * available"), but stay here so resolution recovers automatically if they start working again.
     */
    private val FALLBACK_CLIENTS: List<String?> = listOf("ios", "tv", "android")

    /**
     * Head start (ms) the in-process NewPipeExtractor path gets before the parallel `yt-dlp` subprocess is
     * launched: if NewPipeExtractor yields a full ladder within it, `yt-dlp` is skipped entirely (no spawn).
     * Default 0 launches both at once — `yt-dlp` reaches the PO-token / SABR wall for most videos
     * today, so it runs on nearly every resolve anyway and overlapping it is a pure win. Raise it via
     * `-Ddreamdisplays.ytdlpHedgeMs` where NewPipeExtractor more often ladders and the wasted spawn matters.
     */
    private val HEDGE_DELAY_MS: Long =
        System.getProperty("dreamdisplays.ytdlpHedgeMs")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    /** All yt-dlp background work (prewarm, fetch, per-client races, search) runs on the shared
     *  [DreamCoroutines.clientIo] scope's elastic [kotlinx.coroutines.Dispatchers.IO] pool. */
    private val cookies = YtCookieManager()

    private val formatMemo = AsyncMemo<String, List<YtStream>>(200, CACHE_TTL_MS, DreamCoroutines.clientIo, "fetch")

    /**
     * When each URL's streams were resolved (or disk-promoted), so live entries — whose playlist
     * URLs carry expiring tokens — can be refreshed after [FormatDiskCache.LIVE_TTL_MS] instead of
     * sitting in [formatMemo] for the full 5h TTL.
     */
    private val fetchedAtMs: Cache<String, Long> = Caffeine.newBuilder().maximumSize(300).build()
    private val searchMemo =
        AsyncMemo<String, List<MediaSearchResult>>(100, INFO_CACHE_TTL_MS, DreamCoroutines.clientIo, "search")
    private val relatedMemo =
        AsyncMemo<String, List<MediaSearchResult>>(200, INFO_CACHE_TTL_MS, DreamCoroutines.clientIo, "related")

    /**
     * Returns the stream list for [videoUrl], hitting the in-memory cache, then disk cache, then running `yt-dlp`.
     * Blocks the calling thread until the result is available.
     * @throws IOException on `yt-dlp` failure or timeout.
     */
    @Throws(IOException::class)
    fun fetch(videoUrl: String): List<YtStream> {
        if (!MediaUrlPolicy.isAllowed(videoUrl)) {
            return emptyList()
        }

        var streams = loadFromDisk(videoUrl) ?: formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        if (isStaleLive(videoUrl, streams) || isStalePartial(videoUrl, streams)) {
            invalidateCache(videoUrl)
            streams = formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
        }
        return streams
    }

    /**
     * Whether a cached live result for [videoUrl] has outlived [FormatDiskCache.LIVE_TTL_MS]: live
     * playlist URLs carry expiring tokens (Twitch usher, YouTube live manifests), so serving one
     * from the 5h memo hands the player a dead URL.
     */
    private fun isStaleLive(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.none { it.isLive }) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.LIVE_TTL_MS
    }

    /**
     * Whether a cached partial (non-ladder) result for [videoUrl] has outlived
     * [FormatDiskCache.PARTIAL_TTL_MS]. Videos hitting the PO-token / SABR wall are re-checked on this
     * cadence instead of every single call — see [offersFullResult].
     */
    private fun isStalePartial(videoUrl: String, streams: List<YtStream>): Boolean {
        if (streams.isEmpty() || offersFullResult(streams)) return false
        val at = fetchedAtMs.getIfPresent(videoUrl) ?: return true
        return System.currentTimeMillis() - at > FormatDiskCache.PARTIAL_TTL_MS
    }

    /**
     * Whether [streams] is a result worth caching at the full TTL: a real quality ladder, or a live
     * stream (which legitimately exposes a single height and must not be re-resolved every play).
     */
    private fun offersFullResult(streams: List<YtStream>): Boolean =
        streams.any { it.isLive } || YtStreams.offersQualityLadder(streams)

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        DreamCoroutines.clientIo.launch {
            runCatching {
                NewPipeResolver.ensureInitialized()
                NewPipeResolver.prewarmPlayer()
                YtDlpBinary.resolveCommand()
                cookies.prewarm()
            }.onFailure { e ->
                logger.warn("Failed to prewarm yt-dlp", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    @Suppress("DeferredResultUnused")
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        if (!MediaUrlPolicy.isAllowed(videoUrl)) return
        val cached = formatMemo.peekFresh(videoUrl) ?: loadFromDisk(videoUrl)
        if (cached != null && !isStaleLive(videoUrl, cached) && !isStalePartial(videoUrl, cached)) return
        if (cached != null) invalidateCache(videoUrl)
        formatMemo.load(videoUrl) { fetchAndPersist(it) }
    }

    /** Returns the disk-cached streams for [videoUrl] if fresh, promoting them into the in-memory cache. */
    private fun loadFromDisk(videoUrl: String): List<YtStream>? {
        formatMemo.peekFresh(videoUrl)?.let { return it }
        val fromDisk = FormatDiskCache.load(videoUrl, CACHE_TTL_MS)?.takeIf { it.isNotEmpty() } ?: return null
        val immutable = fromDisk.toList()
        formatMemo.put(videoUrl, immutable)
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        return immutable
    }

    /**
     * Resolves streams for [videoUrl] and mirrors the result into the disk cache. Partial (non-ladder)
     * results are persisted too — [FormatDiskCache.load] caps their effective age at
     * [FormatDiskCache.PARTIAL_TTL_MS] on its own, so this does not risk serving a stale wall result
     * past its recheck window.
     */
    @Throws(IOException::class)
    private suspend fun fetchAndPersist(videoUrl: String): List<YtStream> {
        val streams = fetchUncached(videoUrl).toList()
        fetchedAtMs.put(videoUrl, System.currentTimeMillis())
        if (streams.isNotEmpty()) FormatDiskCache.saveAsync(videoUrl, streams)
        return streams
    }

    /** Searches YouTube for [query] via InnerTube, returning up to [limit] results; uses a 30-minute in-memory cache. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<MediaSearchResult> {
        if (query.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        val key = query.trim().lowercase(Locale.ENGLISH) + "|" + n
        return searchMemo.getBlocking(key, timeoutSeconds = 30) {
            YouTubeInnerTube.search(query.trim(), n).toList()
        }
    }

    /** Fetches up to [limit] related videos for [videoId] via InnerTube; falls back to title search if none found. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<MediaSearchResult> {
        if (videoId.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        return relatedMemo.getBlocking("$videoId|$n", timeoutSeconds = 30) {
            val nextResult = YouTubeInnerTube.next(videoId)
            var hits = ArrayList(nextResult.related)
            hits.removeAll { it.id == videoId }
            // If no related found, fall back to searching by title
            if (hits.isEmpty() && !nextResult.title.isNullOrBlank()) {
                hits = ArrayList(YouTubeInnerTube.search(nextResult.title, n + 2))
                hits.removeAll { it.id == videoId }
            }
            if (hits.size > n) hits = ArrayList(hits.subList(0, n))
            hits.toList()
        }
    }

    /** Fetches the first page (up to [limit] results) matching [query]; a fresh network call each time (continuation isn't cacheable). */
    @Throws(IOException::class)
    fun searchPage(query: String, limit: Int): MediaSearchPage {
        if (query.isBlank()) return MediaSearchPage(emptyList(), null)
        return YouTubeInnerTube.searchPage(query.trim(), limit.coerceIn(1, 25))
    }

    /** Fetches the page following [continuationToken] from a prior [searchPage]/[searchMore] call. */
    @Throws(IOException::class)
    fun searchMore(continuationToken: String, limit: Int): MediaSearchPage =
        YouTubeInnerTube.searchMore(continuationToken, limit.coerceIn(1, 25))

    /** Fetches the first page (up to [limit] results) related to [videoId]. */
    @Throws(IOException::class)
    fun relatedPage(videoId: String, limit: Int): MediaSearchPage {
        if (videoId.isBlank()) return MediaSearchPage(emptyList(), null)
        return YouTubeInnerTube.relatedPage(videoId, limit.coerceIn(1, 25))
    }

    /** Fetches the page following [continuationToken] from a prior [relatedPage]/[relatedMore] call. */
    @Throws(IOException::class)
    fun relatedMore(continuationToken: String, limit: Int): MediaSearchPage =
        YouTubeInnerTube.relatedMore(continuationToken, limit.coerceIn(1, 25))

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? = YouTubeUrls.extractVideoId(url)

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidateCache(videoUrl: String) {
        formatMemo.invalidate(videoUrl)
        fetchedAtMs.invalidate(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    /** Returns a cached YouTube cookie header string for use by HTTP clients other than `yt-dlp`. */
    fun getPublicCookieHeader(): String? = cookies.header()

    /**
     * Resolves streams for [videoUrl]. Runs the in-process [NewPipeResolver] fast path and the
     * `yt-dlp` subprocess ([raceClients]) concurrently, so resolution costs max(NewPipe, yt-dlp)
     * instead of their sum. NewPipe still wins outright when it yields a full ladder (cheaper, no
     * subprocess), in which case the in-flight `yt-dlp` is aborted; otherwise the `yt-dlp` result is
     * awaited, falling back to a NewPipe muxed-only result if every client failed. `yt-dlp` only
     * starts after [HEDGE_DELAY_MS], so a fast NewPipe ladder can skip it entirely.
     */
    @Throws(IOException::class)
    private suspend fun fetchUncached(videoUrl: String): List<YtStream> {
        val ytProcesses = CopyOnWriteArrayList<Process>()
        val abandoned = AbandonFlag()
        val ytdlp = DreamCoroutines.clientIo.async {
            if (HEDGE_DELAY_MS > 0) delay(HEDGE_DELAY_MS.milliseconds)
            if (abandoned.isAbandoned) return@async emptyList()
            raceClients(videoUrl) { proc ->
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

        if (YtStreams.offersQualityLadder(viaNewPipe)) {
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
            withTimeoutOrNull((FETCH_TIMEOUT_SECONDS + 15).seconds) { ytdlp.await() }
                ?: emptyList<YtStream>().also { abandonYtDlp() }
        }.onFailure { e ->
            abandonYtDlp()
            logger.debug("yt-dlp resolve did not settle for {}: {}.", videoUrl, e.message?.take(200))
        }.getOrElse { emptyList() }

        return when {
            YtStreams.offersQualityLadder(viaYtDlp) -> viaYtDlp
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

    /**
     * Resolves [videoUrl] via `yt-dlp`. Tries [PRIMARY_CLIENT] alone first (one request, fast, the
     * only client that currently yields a ladder); only if it produces nothing usable does it fall
     * back to racing [FALLBACK_CLIENTS] in parallel. With browser cookies configured, a single
     * cookie-backed invocation is run instead (no client arg, no race). Returns null on total failure.
     */
    private suspend fun raceClients(videoUrl: String, onProcess: (Process) -> Unit): List<YtStream>? {
        if (!cookies.disabledByConfig()) {
            return runCatchingClient(videoUrl, null, onProcess)?.takeIf { it.isNotEmpty() }
        }
        runCatchingClient(videoUrl, PRIMARY_CLIENT, onProcess)?.takeIf { it.isNotEmpty() }?.let { return it }
        return raceParallel(videoUrl, FALLBACK_CLIENTS, onProcess)
    }

    /** Runs a single [client] fetch, reporting its subprocess to [onProcess]; returns null instead of throwing. */
    private fun runCatchingClient(videoUrl: String, client: String?, onProcess: (Process) -> Unit): List<YtStream>? =
        runCatching {
            runClientFetch(videoUrl, client, onProcess)
        }.onFailure { e ->
            logger.debug("yt-dlp client {} failed for {}: {}.", client ?: "cookies", videoUrl, e.message?.take(200))
        }.getOrNull()

    /**
     * Races [clients] (one subprocess each) in parallel: the first to yield a quality ladder wins
     * immediately and the still-running losers are killed; otherwise, once every client finishes,
     * [bestResult] picks the strongest result. Returns null when every client failed.
     */
    @Suppress("CoroutineContextWithJob")
    private suspend fun raceParallel(
        videoUrl: String,
        clients: List<String?>,
        onProcess: (Process) -> Unit
    ): List<YtStream>? {
        val processes = CopyOnWriteArrayList<Process>()
        val results = CopyOnWriteArrayList<List<YtStream>>()
        val winner = CompletableDeferred<List<YtStream>>()
        val remaining = AtomicInteger(clients.size)

        val runRace: suspend () -> List<YtStream> = {
            coroutineScope {
                for (client in clients) {
                    launch(DreamCoroutines.clientIo.coroutineContext) {
                        runCatching {
                            val streams = runClientFetch(videoUrl, client) { proc ->
                                processes.add(proc)
                                onProcess(proc)
                                if (winner.isCompleted) Processes.destroyTree(proc)
                            }
                            if (streams.isNotEmpty()) results.add(streams)
                            if (YtStreams.offersQualityLadder(streams)) winner.complete(streams)
                        }.onFailure { e ->
                            if (e is CancellationException) throw e
                            logger.debug(
                                "yt-dlp client {} failed for {}: {}",
                                client ?: "cookies",
                                videoUrl,
                                e.message?.take(200)
                            )
                        }.also { result ->
                            if (result.exceptionOrNull() is CancellationException) return@launch

                            if (remaining.decrementAndGet() == 0) {
                                winner.complete(bestResult(results))
                            }
                        }
                    }
                }
                winner.await()
            }
        }

        val finalResult = try {
            withTimeoutOrNull((FETCH_TIMEOUT_SECONDS + 10).seconds) { runRace() } ?: run {
                logger.warn("yt-dlp race for $videoUrl did not settle.")
                emptyList()
            }
        } finally {
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }

        return finalResult.takeIf { it.isNotEmpty() }
    }

    /** Picks the strongest raced result: a quality ladder first, then the one with the most heights. */
    private fun bestResult(results: List<List<YtStream>>): List<YtStream> =
        results.maxWithOrNull(
            compareBy<List<YtStream>> { if (YtStreams.offersQualityLadder(it)) 1 else 0 }
                .thenBy { YtStreams.distinctHeights(it).size }
        ) ?: emptyList()

    /**
     * Runs a single `yt-dlp` invocation for [videoUrl] with the given [client] (null = let yt-dlp
     * choose, used on the cookie path). [onStarted] receives the live process so the racer can kill
     * it once another client wins. Returns the parsed streams; throws on non-zero exit or timeout.
     */
    @Throws(IOException::class)
    private fun runClientFetch(videoUrl: String, client: String?, onStarted: (Process) -> Unit): List<YtStream> {
        val cmd = ArrayList<String>()
        cmd.addAll(YtDlpBinary.resolveCommand())
        val tempCookies = cookies.appendArgs(cmd)

        val hasCookieArg = cmd.any { it == "--cookies" || it == "--cookies-from-browser" }
        if (!hasCookieArg && client != null) {
            cmd.addAll(listOf("--extractor-args", "youtube:player_client=$client"))
        }

        cmd.addAll(
            listOf(
                "--force-ipv4",
                "-J", "--no-playlist", "--no-warnings", "--no-check-formats",
                "--ignore-config", "--no-mark-watched",
                "--extractor-retries", "0",
                "--socket-timeout", "8",
                "--",
                videoUrl,
            )
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
        onStarted(process)
        runCatching { process.outputStream.close() }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Processes.collector(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = Processes.collector(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Processes.destroyTree(process)
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                throw IOException("Timed out for url: $videoUrl (client=${client ?: "cookies"}).")
            }
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for yt-dlp.", e)
        } finally {
            if (tempCookies != null) runCatching { Files.deleteIfExists(tempCookies) }
        }
        if (process.exitValue() != 0) {
            throw IOException("Exited with code ${process.exitValue()}: ${stderr.toString().trim()}.")
        }
        return YtDlpOutputParser.parseFormats(stdout.toString())
    }
}
