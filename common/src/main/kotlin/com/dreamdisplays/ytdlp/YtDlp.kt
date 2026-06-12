package com.dreamdisplays.ytdlp

import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.media.api.YouTubeUrls
import com.dreamdisplays.utils.AsyncMemo
import com.dreamdisplays.utils.Processes
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `yt-dlp` orchestrator: caching and request deduplication around the NewPipe fast path and the
 * `yt-dlp` subprocess. Binary provisioning lives in [YtDlpBinary], cookies in [YtCookieManager],
 * and `-J` output parsing in [YtDlpOutputParser].
 */
object YtDlp {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")

    private const val CACHE_TTL_MS: Long = 5L * 60L * 60L * 1_000L
    private const val INFO_CACHE_TTL_MS: Long = 30L * 60L * 1_000L

    /** Per-invocation yt-dlp wait. With cookies off + token-free clients a warm fetch is sub-second,
     *  so this only bounds genuine failures; keep it short so they surface fast. */
    private const val FETCH_TIMEOUT_SECONDS: Long = 25L

    private val PREWARM_EXECUTOR = Executors.newSingleThreadExecutor { r ->
        Thread(r, "YtDlp-prewarm").apply { isDaemon = true }
    }
    private val FETCH_EXECUTOR = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "YtDlp-fetch").apply { isDaemon = true }
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
        loadFromDisk(videoUrl)?.let { return it }
        return formatMemo.getBlocking(videoUrl) { fetchAndPersist(it) }
    }

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        PREWARM_EXECUTOR.submit {
            try {
                NewPipeResolver.ensureInitialized()
                YtDlpBinary.resolve(PREWARM_EXECUTOR)
                cookies.prewarm()
            } catch (e: IOException) {
                logger.warn("Failed to prewarm yt-dlp", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
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
     * Resolves streams for [videoUrl]. Tries the in-process [NewPipeResolver] fast path first and
     * falls back to the `yt-dlp` subprocess (with one automatic retry on non-timeout errors).
     */
    @Throws(IOException::class)
    private fun fetchUncached(videoUrl: String): List<YtStream> {
        val viaNewPipe = NewPipeResolver.fetch(videoUrl)
        if (offersQualityLadder(viaNewPipe)) return viaNewPipe
        if (viaNewPipe.isNotEmpty()) {
            logger.info(
                "NewPipe returned no quality ladder for $videoUrl " +
                        "(heights=${viaNewPipe.filter { it.hasVideo() }.mapNotNull { it.height }.distinct()}); " +
                        "falling back to yt-dlp."
            )
        }

        var lastError: IOException? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) {
                try {
                    Thread.sleep(2_000)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted before yt-dlp retry", ie)
                }
            }
            try {
                return fetchUncachedOnce(videoUrl, attempt)
            } catch (e: IOException) {
                logger.warn("yt-dlp fetch attempt $attempt failed for $videoUrl: ${e.message?.take(500)}.")
                if (e.message?.contains("timed out", ignoreCase = true) == true) throw e
                if (e.message?.contains("DRM protected", ignoreCase = true) == true) throw e
                lastError = e
            }
        }
        // yt-dlp failed entirely; a 360p-only NewPipe result still beats no playback at all
        if (viaNewPipe.isNotEmpty()) {
            logger.warn("yt-dlp failed for $videoUrl; using NewPipe streams without a quality ladder.")
            return viaNewPipe
        }
        throw lastError!!
    }

    /**
     * True when [streams] gives the player an actual quality choice. YouTube's adaptive
     * (video-only) tracks are often unavailable to the NewPipe fast path, leaving only the
     * muxed 360p stream; in that case the slower yt-dlp path is worth the fallback.
     */
    private fun offersQualityLadder(streams: List<YtStream>): Boolean {
        val heights = streams.asSequence()
            .filter { it.hasVideo() }
            .mapNotNull { it.height }
            .distinct()
            .toList()
        return heights.size >= 2 || (heights.maxOrNull() ?: 0) >= 720
    }

    /**
     * Single `yt-dlp` invocation for [videoUrl]. On [attempt] 0 uses web / ios / mweb clients;
     * on [attempt] 1 falls back to android / tv_embedded / mweb for better bot resistance.
     */
    @Throws(IOException::class)
    private fun fetchUncachedOnce(videoUrl: String, attempt: Int = 0): List<YtStream> {
        val binary = YtDlpBinary.resolve(PREWARM_EXECUTOR)
        val cmd = ArrayList<String>()
        cmd.add(binary)
        val tempCookies = cookies.appendArgs(cmd)

        val hasCookieArg = cmd.any { it == "--cookies" || it == "--cookies-from-browser" }
        if (!hasCookieArg) {
            // Token-free, non-DRM clients only
            val clients = if (attempt == 0) "android_vr,ios,tv" else "tv,android,ios"
            cmd.addAll(listOf("--extractor-args", "youtube:player_client=$clients"))
        }

        cmd.addAll(
            listOf(
                "--force-ipv4",
                "-J", "--no-playlist", "--no-warnings", "--no-check-formats",
                "--ignore-config", "--no-mark-watched",
                "--extractor-retries", "0",
                "--socket-timeout", "8",
                videoUrl,
            )
        )
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
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
                val pid = try {
                    process.pid()
                } catch (_: Exception) {
                    -1L
                }
                val alive = process.isAlive
                Processes.destroyTree(process)
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                logger.warn(
                    "Fetch timeout after ${FETCH_TIMEOUT_SECONDS}s for $videoUrl " +
                            "(pid=$pid, alive=$alive, stdoutBytes=${stdout.length}, stderrBytes=${stderr.length}, " +
                            "stderrTail=${stderr.takeLast(500).trim()}, " +
                            "stdoutTail=${stdout.takeLast(200).trim()})"
                )
                throw IOException("Timed out for url: $videoUrl.")
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
