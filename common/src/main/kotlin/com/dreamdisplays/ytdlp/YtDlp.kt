package com.dreamdisplays.ytdlp

import com.dreamdisplays.Initializer
import com.dreamdisplays.managers.ClientStateManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.round

/**
 * `yt-dlp` main orchestrator. Handles locating the binary, executing it to fetch video info, searching, and related
 * videos.
 */
object YtDlp {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")

    private val CANDIDATE_PATHS = arrayOf(
        "yt-dlp",
        "/opt/homebrew/bin/yt-dlp",
        "/usr/local/bin/yt-dlp",
        "/usr/bin/yt-dlp",
        "C:\\Program Files\\yt-dlp\\yt-dlp.exe",
    )
    private val BUNDLED_DIR: Path = Path.of("libs", "yt-dlp")
    private const val DOWNLOAD_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    private const val CACHE_TTL_MS: Long = 5L * 60L * 60L * 1_000L
    private const val INFO_CACHE_TTL_MS: Long = 30L * 60L * 1_000L
    private const val COOKIE_REFRESH_MS: Long = 2L * 60L * 60L * 1_000L

    private val BROWSER_CANDIDATES = arrayOf("chrome", "firefox", "safari", "edge", "brave", "opera", "vivaldi")
    private val BROWSER_CANDIDATES_MACOS = arrayOf("safari", "firefox", "chrome", "edge", "brave", "opera", "vivaldi")

    private val PREWARM_EXECUTOR = Executors.newSingleThreadExecutor { r ->
        Thread(r, "YtDlp-prewarm").apply { isDaemon = true }
    }
    private val FETCH_EXECUTOR = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "YtDlp-fetch").apply { isDaemon = true }
    }
    private val SEARCH_EXECUTOR = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "YtDlp-search").apply { isDaemon = true }
    }

    private val FORMAT_CACHE: MutableMap<String, CacheEntry> = lruCache(200)
    private val IN_FLIGHT_FETCHES: ConcurrentMap<String, CompletableFuture<List<YtStream>>> = ConcurrentHashMap()
    private val SEARCH_CACHE: MutableMap<String, InfoCacheEntry> = lruCache(100)
    private val RELATED_CACHE: MutableMap<String, InfoCacheEntry> = lruCache(200)
    private val IN_FLIGHT_SEARCHES: ConcurrentMap<String, CompletableFuture<List<YtVideoInfo>>> = ConcurrentHashMap()
    private val IN_FLIGHT_RELATED: ConcurrentMap<String, CompletableFuture<List<YtVideoInfo>>> = ConcurrentHashMap()

    private fun <K, V> lruCache(maxSize: Int): MutableMap<K, V> =
        Collections.synchronizedMap(object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<K, V>) = size > maxSize
        })

    @Volatile private var resolvedBinary: String? = null
    @Volatile private var resolvedCookieBrowser: String? = null
    @Volatile private var cookieBrowserResolved: Boolean = false
    private const val COOKIE_BROWSER_RETRY_MS: Long = 60L * 60L * 1_000L
    @Volatile private var cachedCookieHeader: String? = null
    @Volatile private var cookieHeaderExportedAt: Long = 0
    @Volatile private var cookieBrowserResolvedAt: Long = 0

    /**
     * Returns the stream list for [videoUrl], hitting the in-memory cache, then disk cache, then running `yt-dlp`.
     * Blocks the calling thread until the result is available.
     * @throws IOException on `yt-dlp` failure or timeout.
     */
    @Throws(IOException::class)
    fun fetch(videoUrl: String): List<YtStream> {
        val cached = FORMAT_CACHE[videoUrl]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= CACHE_TTL_MS) return cached.streams

        FormatDiskCache.load(videoUrl, CACHE_TTL_MS)?.takeIf { it.isNotEmpty() }?.let { fromDisk ->
            val immutable = fromDisk.toList()
            FORMAT_CACHE[videoUrl] = CacheEntry(immutable, System.currentTimeMillis())
            return immutable
        }

        val future = startFetchInternal(videoUrl)
        try {
            return future.get()
        } catch (e: CompletionException) {
            throw (e.cause as? IOException) ?: IOException("Fetch failed for url: $videoUrl", e.cause ?: e)
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("Fetch failed for url: $videoUrl", e.cause ?: e)
        } finally {
            IN_FLIGHT_FETCHES.remove(videoUrl, future)
        }
    }

    /** Resolves the binary path, cookie browser, and cookie header in the background to reduce first-fetch latency. */
    fun prewarmAsync() {
        if (resolvedBinary != null && cookieBrowserResolved && cachedCookieHeader != null) return
        PREWARM_EXECUTOR.submit {
            try {
                resolveBinary()
                resolveCookieBrowser()
                getCookieHeader()
            } catch (e: IOException) {
                logger.warn("Failed to prewarm yt-dlp", e)
            }
        }
    }

    /** Fires a background fetch for [videoUrl] if not already cached, so it is ready before [fetch] is called. */
    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        val cached = FORMAT_CACHE[videoUrl]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= CACHE_TTL_MS) return
        FormatDiskCache.load(videoUrl, CACHE_TTL_MS)?.takeIf { it.isNotEmpty() }?.let { fromDisk ->
            FORMAT_CACHE[videoUrl] = CacheEntry(fromDisk.toList(), System.currentTimeMillis())
            return
        }
        startFetchInternal(videoUrl)
    }

    /** Returns a shared [CompletableFuture] for [videoUrl], deduplicating concurrent requests via [IN_FLIGHT_FETCHES]. */
    private fun startFetchInternal(videoUrl: String): CompletableFuture<List<YtStream>> =
        IN_FLIGHT_FETCHES.computeIfAbsent(videoUrl) {
            CompletableFuture.supplyAsync({
                try {
                    val streams = fetchUncached(videoUrl).toList()
                    FORMAT_CACHE[videoUrl] = CacheEntry(streams, System.currentTimeMillis())
                    FormatDiskCache.saveAsync(videoUrl, streams)
                    streams
                } catch (e: IOException) {
                    throw CompletionException(e)
                }
            }, FETCH_EXECUTOR)
        }

    /** Searches YouTube for [query] via InnerTube, returning up to [limit] results; uses a 30-minute in-memory cache. */
    @Throws(IOException::class)
    fun search(query: String, limit: Int): List<YtVideoInfo> {
        if (query.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        val key = query.trim().lowercase(Locale.ENGLISH) + "|" + n
        val cached = SEARCH_CACHE[key]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= INFO_CACHE_TTL_MS) return cached.results

        val future = IN_FLIGHT_SEARCHES.computeIfAbsent(key) {
            CompletableFuture.supplyAsync({
                try {
                    val results = YouTubeInnerTube.search(query.trim(), n).toList()
                    SEARCH_CACHE[key] = InfoCacheEntry(results, System.currentTimeMillis())
                    results
                } catch (e: Exception) {
                    throw e as? CompletionException
                        ?: CompletionException(
                            e as? IOException ?: IOException("InnerTube search failed", e)
                        )
                }
            }, SEARCH_EXECUTOR)
        }
        return waitForInfoFuture(future, "search '$query'") { IN_FLIGHT_SEARCHES.remove(key, future) }
    }

    /** Fetches up to [limit] related videos for [videoId] via InnerTube; falls back to title search if none found. */
    @Throws(IOException::class)
    fun related(videoId: String, limit: Int): List<YtVideoInfo> {
        if (videoId.isBlank()) return ArrayList()
        val n = limit.coerceIn(1, 25)
        val key = "$videoId|$n"
        val cached = RELATED_CACHE[key]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= INFO_CACHE_TTL_MS) return cached.results

        val future = IN_FLIGHT_RELATED.computeIfAbsent(key) {
            CompletableFuture.supplyAsync({
                try {
                    val nextResult = YouTubeInnerTube.next(videoId)
                    var hits = ArrayList(nextResult.related)
                    hits.removeAll { it.id == videoId }
                    // If no related found, fall back to searching by title
                    if (hits.isEmpty() && !nextResult.title.isNullOrBlank()) {
                        hits = ArrayList(YouTubeInnerTube.search(nextResult.title, n + 2))
                        hits.removeAll { it.id == videoId }
                    }
                    if (hits.size > n) hits = ArrayList(hits.subList(0, n))
                    val immutable = hits.toList()
                    RELATED_CACHE[key] = InfoCacheEntry(immutable, System.currentTimeMillis())
                    immutable
                } catch (e: Exception) {
                    throw e as? CompletionException
                        ?: CompletionException(
                            e as? IOException ?: IOException("InnerTube related failed", e)
                        )
                }
            }, SEARCH_EXECUTOR)
        }
        return waitForInfoFuture(future, "related $videoId") { IN_FLIGHT_RELATED.remove(key, future) }
    }

    /** Extracts the 11-character YouTube video ID from a full URL, short URL, or bare ID. Returns null if not recognized. */
    fun extractVideoId(url: String?): String? {
        if (url == null) return null
        val s = url.trim()
        if (s.isEmpty()) return null
        if (s.length == 11 && s.matches(Regex("[A-Za-z0-9_-]{11}"))) return s
        try {
            val uri = URI.create(s)
            val host = uri.host?.lowercase(Locale.ENGLISH) ?: return null
            val path = uri.path ?: ""
            if ("youtu.be" in host) {
                val p = if (path.startsWith("/")) path.substring(1) else path
                val slash = p.indexOf('/')
                return if (slash >= 0) p.substring(0, slash) else p
            }
            if ("youtube.com" in host) {
                uri.query?.split('&')?.forEach { part ->
                    if (part.startsWith("v=")) return part.substring(2)
                }
                if (path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/")) {
                    val rest = path.substring(path.indexOf('/', 1) + 1)
                    val slash = rest.indexOf('/')
                    return if (slash >= 0) rest.substring(0, slash) else rest
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Appends proxy and cookie arguments to the `yt-dlp` command [args].
     * Prefers a materialized cookies.txt file; falls back to `--cookies-from-browser` if the file doesn't exist.
     * Returns the path to a temp cookie copy (to be deleted after the process exits), or null.
     */
    private fun addCookieArgs(args: MutableList<String>): Path? {
        val proxy = ClientStateManager.config.ytdlpProxy.trim()
        if (proxy.isNotEmpty()) {
            args.add("--proxy")
            args.add(proxy)
        }
        if (cookiesDisabledByConfig()) return null

        val cookieFile = BUNDLED_DIR.resolve("cookies.txt")
        if (!Files.exists(cookieFile) && !cookiesUnavailableThisSession) ensureCookieFileMaterialized(cookieFile)
        if (Files.exists(cookieFile)) {
            val tempCookies = try {
                val target = Files.createTempFile(BUNDLED_DIR, "cookies-", ".txt")
                Files.copy(cookieFile, target, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (e: IOException) {
                logger.warn("Failed to create temp cookies copy, using master file: ${e.message}")
                null
            }
            args.add("--cookies")
            args.add((tempCookies ?: cookieFile).toString())
            try {
                val age = System.currentTimeMillis() - Files.getLastModifiedTime(cookieFile).toMillis()
                if (age >= COOKIE_REFRESH_MS) {
                    refreshCookiesAsync()
                }
            } catch (_: IOException) {
            }
            return tempCookies
        }
        if (!cookiesUnavailableThisSession) {
            resolveCookieBrowser()?.let {
                args.add("--cookies-from-browser")
                args.add(it)
            }
        }
        return null
    }

    private fun cookiesDisabledByConfig(): Boolean {
        val configured = ClientStateManager.config.ytdlpCookiesFromBrowser.trim().lowercase(Locale.ENGLISH)
        return configured == "none" || configured == "off" || configured == "disabled" || configured.isEmpty()
    }

    /**
     * Cross-process lock for serializing `--cookies-from-browser` access across multiple game
     * installs on the same machine. Located in the system temp dir so all installs see the same
     * lock file.
     */
    private val COOKIE_EXPORT_LOCK_FILE: Path =
        Path.of(System.getProperty("java.io.tmpdir"), "dreamdisplays-yt-cookies.lock")

    /**
     * Set to true after a cookie export failure (timeout, hang, permission denied, etc.) to stop
     * banging on `--cookies-from-browser` for the rest of the session. The user can still play
     * public videos without cookies; the alternative is every yt-dlp call hanging for 60 s.
     */
    @Volatile private var cookiesUnavailableThisSession = false

    /**
     * Synchronously exports cookies to [cookieFile] under a cross-process file lock. No-op if
     * another process already created the file while we were waiting. Returns silently on any
     * failure, the caller falls back to `--cookies-from-browser` directly in that case.
     */
    private fun ensureCookieFileMaterialized(cookieFile: Path) {
        if (resolveCookieBrowser() == null) return
        try {
            Files.createDirectories(COOKIE_EXPORT_LOCK_FILE.parent)
            RandomAccessFile(COOKIE_EXPORT_LOCK_FILE.toFile(), "rw").use { raf ->
                raf.channel.lock().use {
                    if (Files.exists(cookieFile)) return
                    try {
                        exportCookieHeader()
                    } catch (e: Exception) {
                        logger.warn("Synchronous cookie export failed: ${e.message}")
                    }
                    if (!Files.exists(cookieFile)) {
                        cookiesUnavailableThisSession = true
                        logger.warn(
                            "Cookie export produced no file. Disabling browser cookie lookups for this session. Public videos will still work."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not acquire cookie export lock: ${e.message}")
        }
    }

    private val cookieRefreshInProgress = AtomicBoolean(false)

    /** Re-exports the browser cookie file in the background if not already in progress. */
    private fun refreshCookiesAsync() {
        if (!cookieRefreshInProgress.compareAndSet(false, true)) return
        PREWARM_EXECUTOR.submit {
            try {
                exportCookieHeader()
            } catch (_: Exception) {
            } finally {
                cookieRefreshInProgress.set(false)
            }
        }
    }

    /** Waits up to 30 seconds for [future] to complete, unwraps the exception if it fails, and calls [cleanup] in both cases. */
    @Throws(IOException::class)
    private fun waitForInfoFuture(
        future: CompletableFuture<List<YtVideoInfo>>,
        tag: String,
        cleanup: () -> Unit,
    ): List<YtVideoInfo> {
        try {
            return future.get(30, TimeUnit.SECONDS)
        } catch (e: CompletionException) {
            throw (e.cause as? IOException) ?: IOException("$tag failed", e.cause ?: e)
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("$tag failed", e.cause ?: e)
        } finally {
            cleanup()
        }
    }

    /** Runs `yt-dlp` for [videoUrl] with one automatic retry on non-timeout errors. */
    @Throws(IOException::class)
    private fun fetchUncached(videoUrl: String): List<YtStream> {
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
                if (e.message?.contains("timed out") == true) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    /**
     * Single `yt-dlp` invocation for [videoUrl]. On [attempt] 0 uses web / ios / mweb clients;
     * on [attempt] 1 falls back to android / tv_embedded / mweb for better bot resistance.
     */
    @Throws(IOException::class)
    private fun fetchUncachedOnce(videoUrl: String, attempt: Int = 0): List<YtStream> {
        val binary = resolveBinary()
        val cmd = ArrayList<String>()
        cmd.add(binary)
        val tempCookies = addCookieArgs(cmd)

        val hasCookieArg = cmd.any { it == "--cookies" || it == "--cookies-from-browser" }
        if (!hasCookieArg) {
            val clients = if (attempt == 0) "web,ios,mweb" else "android,tv_embedded,mweb"
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
        val stdoutReader = streamReader(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = streamReader(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                val pid = try {
                    process.pid()
                } catch (_: Exception) {
                    -1L
                }
                val alive = process.isAlive
                destroyProcessTree(process)
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                logger.warn(
                    "Fetch timeout after 60s for $videoUrl " +
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
        return parseFormats(stdout.toString())
    }

    /** Creates a daemon thread that drains [input] into [sink]; call [Thread.start] on the result. */
    private fun streamReader(input: InputStream, sink: StringBuilder, name: String): Thread {
        val t = Thread({
            try {
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { r ->
                    val buf = CharArray(8192)
                    var n: Int
                    while (r.read(buf).also { n = it } != -1) sink.appendRange(buf, 0, n)
                }
            } catch (_: IOException) {
            }
        }, name)
        t.isDaemon = true
        return t
    }

    /** Removes [videoUrl] from the in-memory format cache, in-flight map, and disk cache. */
    fun invalidateCache(videoUrl: String) {
        FORMAT_CACHE.remove(videoUrl)
        IN_FLIGHT_FETCHES.remove(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    /** Returns a cached YouTube cookie header string for use by HTTP clients other than `yt-dlp`. */
    fun getPublicCookieHeader(): String? = getCookieHeader()

    /** Returns the cached cookie header, re-exporting from the browser if the TTL has expired. Thread-safe. */
    private fun getCookieHeader(): String? {
        val cached = cachedCookieHeader
        val now = System.currentTimeMillis()
        if (cached != null && (now - cookieHeaderExportedAt) < COOKIE_REFRESH_MS) return cached
        synchronized(this) {
            if (cachedCookieHeader != null && (System.currentTimeMillis() - cookieHeaderExportedAt) < COOKIE_REFRESH_MS) {
                return cachedCookieHeader
            }
            return try {
                val header = exportCookieHeader()
                cachedCookieHeader = header
                cookieHeaderExportedAt = System.currentTimeMillis()
                header
            } catch (e: Exception) {
                logger.warn("Cookie export failed: ${e.message}")
                cachedCookieHeader
            }
        }
    }

    /** Runs `yt-dlp --cookies-from-browser` to write cookies.txt, then parses relevant YouTube / Google cookie lines into a header string. */
    @Throws(IOException::class)
    private fun exportCookieHeader(): String? {
        val browser = resolveCookieBrowser() ?: return null
        val binary = resolveBinary()
        val cookieFile = BUNDLED_DIR.resolve("cookies.txt")
        Files.createDirectories(cookieFile.parent)

        val pb = ProcessBuilder(
            binary,
            "--cookies-from-browser", browser,
            "--cookies", cookieFile.toString(),
            "--simulate", "--quiet", "--no-warnings",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        val drainer = Thread {
            try { p.inputStream.use { it.readAllBytes() } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                destroyProcessTree(p)
                cookiesUnavailableThisSession = true
                throw IOException("Cookie export timed out.")
            }
        } catch (e: InterruptedException) {
            destroyProcessTree(p)
            Thread.currentThread().interrupt()
            throw IOException("Interrupted", e)
        }
        try { drainer.join(500) } catch (_: InterruptedException) {}

        if (!Files.exists(cookieFile)) return null
        val lines = Files.readAllLines(cookieFile, StandardCharsets.UTF_8)
        val sb = StringBuilder()
        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size < 7) continue
            val domain = parts[0]
            if ("youtube" !in domain && "google" !in domain) continue
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(parts[5]).append("=").append(parts[6])
        }
        val result = sb.toString()
        if (result.isEmpty()) return null
        logger.info("Exported ${lines.size} cookie lines, header ${result.length} chars.")
        return result
    }

    /** Parses the JSON output of `yt-dlp -J` into a flat list of [YtStream] descriptors. */
    @Throws(IOException::class)
    private fun parseFormats(json: String): List<YtStream> {
        val result = ArrayList<YtStream>()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IOException("Failed to parse yt-dlp JSON output", e)
        }
        if (!root.isJsonObject) throw IOException("Returned unexpected JSON shape.")
        val obj = root.asJsonObject
        val live = isLive(obj)
        val durationNanos = getDurationNanos(obj)
        val seekable = !live && durationNanos > 0L
        if (!obj.has("formats") || !obj.get("formats").isJsonArray) return result

        for (el in obj.getAsJsonArray("formats")) {
            if (!el.isJsonObject) continue
            val f = el.asJsonObject

            val url = optString(f, "url")
            if (url.isNullOrEmpty()) continue

            val protocol = optString(f, "protocol")
            if (!isSupportedProtocol(protocol, url)) continue

            val vcodec = optString(f, "vcodec")
            val acodec = optString(f, "acodec")
            val ext = optString(f, "ext")
            val container = optString(f, "container")

            val hasVideo = vcodec != null && vcodec != "none"
            val hasAudio = acodec != null && acodec != "none"
            if (!hasVideo && !hasAudio) continue

            val mime = (if (hasVideo) "video/" else "audio/") + (ext ?: "webm")

            val width = optInt(f, "width")
            val height = optInt(f, "height")

            val resolution: String? = if (hasVideo) {
                val h = height
                if (h != null && h > 0) "${h}p"
                else extractResolution(optString(f, "resolution"), optString(f, "format_note"), optString(f, "format"))
            } else null

            val language = optString(f, "language")
            val formatNote = optString(f, "format_note")
            val fps = optDouble(f, "fps")
            val tbr = optDouble(f, "tbr")

            result.add(
                YtStream(
                    url, mime, container, protocol, resolution,
                    width, height,
                    language, formatNote, vcodec, acodec, fps, tbr,
                    hasVideo, hasAudio, live, seekable, durationNanos
                )
            )
        }
        return result
    }

    /** Returns true if the `yt-dlp` output object indicates a live or upcoming stream. */
    private fun isLive(obj: JsonObject): Boolean {
        if (optBoolean(obj)) return true
        val liveStatus = optString(obj, "live_status") ?: return false
        return when (liveStatus) {
            "is_live", "is_upcoming", "post_live" -> true
            else -> false
        }
    }

    /** Reads the `duration` field from the JSON object and converts seconds to nanoseconds; returns 0 for live or missing values. */
    private fun getDurationNanos(obj: JsonObject): Long {
        val durationSeconds = optDouble(obj, "duration") ?: return 0L
        if (durationSeconds <= 0.0) return 0L
        val nanos = durationSeconds * 1_000_000_000.0
        if (nanos >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return max(0L, round(nanos).toLong())
    }

    /** Returns true if the stream protocol (or fallback URL extension) is one we can pipe through FFmpeg. */
    private fun isSupportedProtocol(protocol: String?, url: String): Boolean {
        if (protocol.isNullOrBlank()) return true
        if (protocol.startsWith("http")) return true
        if ("m3u8" in protocol) return true
        if ("dash" in protocol) return true
        val lowerUrl = url.lowercase(Locale.ENGLISH)
        return ".m3u8" in lowerUrl || ".mpd" in lowerUrl
    }

    /** Tries each [candidates] string in order, returning the first one that contains a parseable resolution (e.g. "720p"). */
    private fun extractResolution(vararg candidates: String?): String? {
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            var m = Pattern.compile("(\\d{3,4})p").matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
            m = Pattern.compile("(\\d{3,4})").matcher(candidate)
            if (m.find()) return "${m.group(1)}p"
        }
        return null
    }

    /** Safely reads a string field [key] from [obj], returning null if absent, null-valued, or not a string. */
    private fun optString(obj: JsonObject, key: String): String? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            obj.get(key).asString
        } catch (_: Exception) {
            null
        }
    }

    /** Reads an int field from [obj], returning null if absent or not parseable. */
    private fun optInt(obj: JsonObject, key: String): Int? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            obj.get(key).asInt
        } catch (_: Exception) {
            null
        }
    }

    /** Safely reads a double field [key] from [obj], returning null if absent or not parseable. */
    private fun optDouble(obj: JsonObject, key: String): Double? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try {
            obj.get(key).asDouble
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the `is_live` boolean field from [obj], returning false if absent or not parseable. */
    private fun optBoolean(obj: JsonObject): Boolean {
        if (!obj.has("is_live") || obj.get("is_live").isJsonNull) return false
        return try {
            obj.get("is_live").asBoolean
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the path to a usable `yt-dlp` binary, checking well-known system paths first,
     * then the bundled directory, and downloading from GitHub as a last resort.
     */
    @Throws(IOException::class)
    private fun resolveBinary(): String {
        resolvedBinary?.let { return it }
        synchronized(this) {
            resolvedBinary?.let { return it }

            val candidates = ArrayList<String>()
            var override = System.getProperty("dreamdisplays.ytdlp")
            if (override.isNullOrEmpty()) override = System.getenv("DREAMDISPLAYS_YTDLP")
            if (!override.isNullOrEmpty()) candidates.add(override)

            candidates.addAll(CANDIDATE_PATHS)
            val bundled = BUNDLED_DIR.resolve(bundledBinaryName())
            candidates.add(bundled.toString())

            for (c in candidates) {
                if (canExecute(c)) {
                    resolvedBinary = c
                    return c
                }
            }

            val downloaded = downloadBundled(bundled)
            resolvedBinary = downloaded
            return downloaded
        }
    }

    /**
     * Resolves the browser to use for `--cookies-from-browser`, respecting the config setting.
     * The result is cached; null means cookies are disabled or no suitable browser was found.
     */
    private fun resolveCookieBrowser(): String? {
        val now = System.currentTimeMillis()
        if (cookieBrowserResolved && resolvedCookieBrowser != null) return resolvedCookieBrowser
        if (cookieBrowserResolved && (now - cookieBrowserResolvedAt) < COOKIE_BROWSER_RETRY_MS) return null
        synchronized(this) {
            if (cookieBrowserResolved && resolvedCookieBrowser != null) return resolvedCookieBrowser
            if (cookieBrowserResolved && (now - cookieBrowserResolvedAt) < COOKIE_BROWSER_RETRY_MS) return null
            var configured = ClientStateManager.config.ytdlpCookiesFromBrowser
            configured = configured.trim().lowercase(Locale.ENGLISH)

            if (configured == "none" || configured == "off" || configured == "disabled" || configured.isEmpty()) {
                cookieBrowserResolved = true
                resolvedCookieBrowser = null
                logger.info("Cookies-from-browser disabled via config.")
                return null
            }

            // Try to avoid false message popup "Security wants to use your confidential information..." on macOS
            val isMacOs = System.getProperty("os.name", "").lowercase(Locale.ENGLISH).contains("mac")
            val candidates = if (configured == "auto") {
                if (isMacOs) BROWSER_CANDIDATES_MACOS else BROWSER_CANDIDATES
            } else arrayOf(configured)
            val binary: String? = try {
                resolveBinary()
            } catch (_: IOException) {
                cookieBrowserResolved = true
                return null
            }
            if (binary == null) {
                cookieBrowserResolved = true
                return null
            }
            for (browser in candidates) {
                if (testCookieBrowser(binary, browser)) {
                    logger.info("Using cookies from browser: $browser...")
                    resolvedCookieBrowser = browser
                    cookieBrowserResolved = true
                    cookieBrowserResolvedAt = System.currentTimeMillis()
                    return browser
                }
            }
            if (configured != "auto") {
                logger.warn(
                    "Browser '$configured' not found or has no YouTube cookies. " +
                            "YouTube may rate-limit or refuse requests. " +
                            "Set 'ytdlp-cookies-from-browser' to 'none' to disable."
                )
            }
            cookieBrowserResolvedAt = System.currentTimeMillis()
            cookieBrowserResolved = true
            return null
        }
    }

    /** Runs `yt-dlp --dump-user-agent` with [browser] cookies and checks whether the cookie file was produced. */
    private fun testCookieBrowser(binary: String, browser: String): Boolean {
        return try {
            val safeName = browser.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val testCookieFile = BUNDLED_DIR.resolve("cookie-test-$safeName.txt")
            Files.createDirectories(testCookieFile.parent)
            Files.deleteIfExists(testCookieFile)

            val pb = ProcessBuilder(
                binary,
                "--cookies-from-browser", browser,
                "--cookies", testCookieFile.toString(),
                "--dump-user-agent", "--quiet", "--no-warnings",
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            try {
                p.outputStream.close()
            } catch (_: IOException) {
            }
            val drainer = Thread {
                try { p.inputStream.use { it.readAllBytes() } } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                destroyProcessTree(p)
                try { drainer.join(500) } catch (_: InterruptedException) {}
                return false
            }
            try { drainer.join(500) } catch (_: InterruptedException) {}
            if (!Files.exists(testCookieFile)) {
                return p.exitValue() == 0
            }
            val lines = Files.readAllLines(testCookieFile, StandardCharsets.UTF_8)
            Files.deleteIfExists(testCookieFile)
            lines.any { !it.startsWith("#") && it.count { c -> c == '\t' } >= 6 }
        } catch (_: Exception) {
            false
        }
    }

    private fun destroyProcessTree(process: Process) {
        runCatching {
            process.toHandle().descendants()
                .sorted(Comparator.comparingLong<ProcessHandle> { it.pid() }.reversed())
                .forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    /** Returns the expected filename of the bundled binary for the current OS (e.g. `yt-dlp.exe` on Windows). */
    private fun bundledBinaryName(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        return if ("win" in os) "yt-dlp.exe" else "yt-dlp"
    }

    /** Returns the GitHub release asset name to download for the current OS and architecture. */
    private fun downloadAssetName(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
        if ("win" in os) return "yt-dlp.exe"
        if ("mac" in os) return "yt-dlp_macos"
        if ("aarch64" in arch || "arm64" in arch) return "yt-dlp_linux_aarch64"
        if ("arm" in arch) return "yt-dlp_linux_armv7l"
        return "yt-dlp_linux"
    }

    /** Downloads `yt-dlp` from GitHub to [target], marks it executable, and removes the macOS quarantine flag. */
    @Throws(IOException::class)
    private fun downloadBundled(target: Path): String {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.part")
        val url = DOWNLOAD_BASE + downloadAssetName()
        logger.info("Downloading yt-dlp from $url...")

        val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "DreamDisplays-yt-dlp-bootstrap")
        try {
            conn.inputStream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            conn.disconnect()
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        if (!System.getProperty("os.name", "").lowercase(Locale.ENGLISH).contains("win")) {
            try {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            } catch (_: UnsupportedOperationException) {
                target.toFile().setExecutable(true, false)
            }
            try {
                // Hack: this operation is needed for safe FFmpeg execution on macOS, since otherwise the quarantine flag may prevent
                // it from running. It doesn't matter if this fails, since the binary will still work in most cases, but removing the
                // quarantine flag can help avoid some weird issues on macOS where the OS prevents the binary from running due to
                // security concerns.
                ProcessBuilder("xattr", "-d", "com.apple.quarantine", target.toString())
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }

        val path = target.toString()
        logger.info("Ready to work.")
        return path
    }

    /** Returns true if [path] refers to a file that can be executed (or a command on PATH that exits 0). */
    private fun canExecute(path: String): Boolean {
        return try {
            val f = File(path)
            if (f.isAbsolute || File.separator in path) return f.isFile && f.canExecute()
            val pb = ProcessBuilder(path, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            try {
                p.outputStream.close()
            } catch (_: IOException) {
            }
            p.inputStream.use { it.readAllBytes() }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return false
            }
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private data class InfoCacheEntry(val results: List<YtVideoInfo>, val createdAtMs: Long)
    private data class CacheEntry(val streams: List<YtStream>, val createdAtMs: Long)
}
