package com.dreamdisplays.ytdlp

import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.YouTubeUrls
import com.dreamdisplays.protocol.MediaUrlPolicy
import com.dreamdisplays.utils.AsyncMemo
import com.dreamdisplays.utils.Processes
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `yt-dlp` orchestrator: caching and request deduplication around the NewPipe fast path and the
 * `yt-dlp` subprocess. Binary provisioning lives in [YtDlpBinary], cookies in [YtCookieManager],
 * and `-J` output parsing in [YtDlpOutputParser].
 */
object YtDlp {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")

    private val CACHE_TTL_MS: Long = FormatDiskCache.DEFAULT_TTL_MS
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

    private val PREWARM_EXECUTOR = Executors.newSingleThreadExecutor { r ->
        Thread(r, "YtDlp-prewarm").apply { isDaemon = true }
    }
    private val FETCH_EXECUTOR = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "YtDlp-fetch").apply { isDaemon = true }
    }
    /** Per-client race workers; sized on demand since each waits on a blocking subprocess. */
    private val RACE_EXECUTOR = Executors.newCachedThreadPool { r ->
        Thread(r, "YtDlp-race").apply { isDaemon = true }
    }
    private val SEARCH_EXECUTOR = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "YtDlp-search").apply { isDaemon = true }
    }

    private val cookies = YtCookieManager(PREWARM_EXECUTOR)

    private val formatMemo = AsyncMemo<String, List<YtStream>>(200, CACHE_TTL_MS, FETCH_EXECUTOR, "fetch")
    private val searchMemo = AsyncMemo<String, List<MediaSearchResult>>(100, INFO_CACHE_TTL_MS, SEARCH_EXECUTOR, "search")
    private val relatedMemo = AsyncMemo<String, List<MediaSearchResult>>(200, INFO_CACHE_TTL_MS, SEARCH_EXECUTOR, "related")

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

        loadFromDisk(videoUrl)?.let { return it }
        return formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
    }

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        PREWARM_EXECUTOR.submit {
            try {
                NewPipeResolver.ensureInitialized()
                NewPipeResolver.prewarmPlayer()
                YtDlpBinary.resolveCommand(PREWARM_EXECUTOR)
                cookies.prewarm()
            } catch (e: IOException) {
                logger.warn("Failed to prewarm yt-dlp", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        if (!MediaUrlPolicy.isAllowed(videoUrl)) return
        if (formatMemo.peekFresh(videoUrl) != null) return
        if (loadFromDisk(videoUrl) != null) return
        formatMemo.load(videoUrl) { fetchAndPersist(it) }
    }

    /** Returns the disk-cached streams for [videoUrl] if fresh, promoting them into the in-memory cache. */
    private fun loadFromDisk(videoUrl: String): List<YtStream>? {
        formatMemo.peekFresh(videoUrl)?.let { return it }
        val fromDisk = FormatDiskCache.load(videoUrl, CACHE_TTL_MS)?.takeIf { it.isNotEmpty() } ?: return null
        val immutable = fromDisk.toList()
        formatMemo.put(videoUrl, immutable)
        return immutable
    }

    /** Resolves streams for [videoUrl] and mirrors the result into the disk cache. */
    @Throws(IOException::class)
    private fun fetchAndPersist(videoUrl: String): List<YtStream> {
        val streams = fetchUncached(videoUrl).toList()
        FormatDiskCache.saveAsync(videoUrl, streams)
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

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? = YouTubeUrls.extractVideoId(url)

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidateCache(videoUrl: String) {
        formatMemo.invalidate(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    /** Returns a cached YouTube cookie header string for use by HTTP clients other than `yt-dlp`. */
    fun getPublicCookieHeader(): String? = cookies.header()

    /**
     * Resolves streams for [videoUrl]. Tries the in-process [NewPipeResolver] fast path first and,
     * when that returns no quality ladder, races one `yt-dlp` subprocess per client in parallel and
     * takes the first usable result (see [raceClients]).
     */
    @Throws(IOException::class)
    private fun fetchUncached(videoUrl: String): List<YtStream> {
        val viaNewPipe = NewPipeResolver.fetch(videoUrl)
        if (YtStreams.offersQualityLadder(viaNewPipe)) return viaNewPipe
        if (viaNewPipe.isNotEmpty()) {
            logger.debug(
                "NewPipe returned no quality ladder for {} (heights={}); racing yt-dlp clients.",
                videoUrl, YtStreams.distinctHeights(viaNewPipe),
            )
        }

        raceClients(videoUrl)?.let { return it }

        // Every yt-dlp client failed; a 360p-only NewPipeExtractor result still beats no playback at all.
        if (viaNewPipe.isNotEmpty()) {
            logger.warn("yt-dlp clients all failed for $videoUrl; using NewPipeExtractor streams without a quality ladder.")
            return viaNewPipe
        }
        throw IOException("All yt-dlp clients failed for $videoUrl.")
    }

    /**
     * Resolves [videoUrl] via `yt-dlp`. Tries [PRIMARY_CLIENT] alone first (one request, fast, the
     * only client that currently yields a ladder); only if it produces nothing usable does it fall
     * back to racing [FALLBACK_CLIENTS] in parallel. With browser cookies configured, a single
     * cookie-backed invocation is run instead (no client arg, no race). Returns null on total failure.
     */
    private fun raceClients(videoUrl: String): List<YtStream>? {
        if (!cookies.disabledByConfig()) {
            return runCatchingClient(videoUrl, null)?.takeIf { it.isNotEmpty() }
        }
        runCatchingClient(videoUrl, PRIMARY_CLIENT)?.takeIf { it.isNotEmpty() }?.let { return it }
        return raceParallel(videoUrl, FALLBACK_CLIENTS)
    }

    /** Runs a single [client] fetch on the current thread, returning null instead of throwing. */
    private fun runCatchingClient(videoUrl: String, client: String?): List<YtStream>? =
        try {
            runClientFetch(videoUrl, client) { } // Single shot — no concurrent winner to abort against
        } catch (e: Exception) {
            logger.debug("yt-dlp client {} failed for {}: {}", client ?: "cookies", videoUrl, e.message?.take(200))
            null
        }

    /**
     * Races [clients] (one subprocess each) in parallel: the first to yield a quality ladder wins
     * immediately and the still-running losers are killed; otherwise, once every client finishes,
     * [bestResult] picks the strongest result. Returns null when every client failed.
     */
    private fun raceParallel(videoUrl: String, clients: List<String?>): List<YtStream>? {
        val processes = CopyOnWriteArrayList<Process>()
        val results = CopyOnWriteArrayList<List<YtStream>>()
        val winner = CompletableFuture<List<YtStream>>()
        val remaining = AtomicInteger(clients.size)

        for (client in clients) {
            RACE_EXECUTOR.execute {
                try {
                    val streams = runClientFetch(videoUrl, client) { proc ->
                        processes.add(proc)
                        if (winner.isDone) Processes.destroyTree(proc) // race already won; don't bother finishing
                    }
                    if (streams.isNotEmpty()) results.add(streams)
                    if (YtStreams.offersQualityLadder(streams)) winner.complete(streams)
                } catch (e: Exception) {
                    logger.debug("yt-dlp client {} failed for {}: {}", client ?: "cookies", videoUrl, e.message?.take(200))
                } finally {
                    if (remaining.decrementAndGet() == 0) winner.complete(bestResult(results))
                }
            }
        }

        val result = try {
            winner.get(FETCH_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("yt-dlp race for $videoUrl did not settle: ${e.message}.")
            emptyList()
        } finally {
            processes.forEach { runCatching { Processes.destroyTree(it) } }
        }
        return result.takeIf { it.isNotEmpty() }
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
        cmd.addAll(YtDlpBinary.resolveCommand(PREWARM_EXECUTOR))
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
        try {
            process.outputStream.close()
        } catch (_: IOException) {
        }
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
            throw IOException("Interrupted while waiting for yt-dlp", e)
        } finally {
            if (tempCookies != null) try {
                Files.deleteIfExists(tempCookies)
            } catch (_: IOException) {
            }
        }
        if (process.exitValue() != 0) {
            throw IOException("Exited with code ${process.exitValue()}: ${stderr.toString().trim()}.")
        }
        return YtDlpOutputParser.parseFormats(stdout.toString())
    }
}
