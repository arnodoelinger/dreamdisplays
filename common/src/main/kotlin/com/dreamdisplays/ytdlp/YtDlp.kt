package com.dreamdisplays.ytdlp

import com.dreamdisplays.Initializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.inotsleep.utils.logging.LoggingManager
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * `yt-dlp` main orchestrator. Handles locating the binary, executing it to fetch video info, searching, and related
 * videos.
 */
object YtDlp {

    private val CANDIDATE_PATHS = arrayOf(
        "yt-dlp",
        "/opt/homebrew/bin/yt-dlp",
        "/usr/local/bin/yt-dlp",
        "/usr/bin/yt-dlp",
        "C:\\Program Files\\yt-dlp\\yt-dlp.exe",
    )
    private val BUNDLED_DIR: Path = Path.of("libs", "yt-dlp")
    private const val DOWNLOAD_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    private const val CACHE_TTL_MS: Long = 2L * 60L * 60L * 1_000L
    private const val INFO_CACHE_TTL_MS: Long = 30L * 60L * 1_000L
    private const val COOKIE_REFRESH_MS: Long = 2L * 60L * 60L * 1_000L

    private val BROWSER_CANDIDATES = arrayOf("chrome", "firefox", "safari", "edge", "brave", "opera", "vivaldi")
    private val BROWSER_CANDIDATES_MACOS = arrayOf("safari", "firefox", "chrome", "edge", "brave", "opera", "vivaldi")

    private val PREWARM_EXECUTOR = Executors.newSingleThreadExecutor { r ->
        Thread(r, "YtDlp-prewarm").apply { isDaemon = true }
    }
    private val FETCH_EXECUTOR = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "YtDlp-fetch").apply { isDaemon = true }
    }
    private val SEARCH_EXECUTOR = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "YtDlp-search").apply { isDaemon = true }
    }

    private val FORMAT_CACHE: ConcurrentMap<String, CacheEntry> = ConcurrentHashMap()
    private val IN_FLIGHT_FETCHES: ConcurrentMap<String, CompletableFuture<List<YtStream>>> = ConcurrentHashMap()
    private val SEARCH_CACHE: ConcurrentMap<String, InfoCacheEntry> = ConcurrentHashMap()
    private val RELATED_CACHE: ConcurrentMap<String, InfoCacheEntry> = ConcurrentHashMap()
    private val IN_FLIGHT_SEARCHES: ConcurrentMap<String, CompletableFuture<List<YtVideoInfo>>> = ConcurrentHashMap()
    private val IN_FLIGHT_RELATED: ConcurrentMap<String, CompletableFuture<List<YtVideoInfo>>> = ConcurrentHashMap()
    private val TITLE_CACHE: ConcurrentMap<String, TitleCacheEntry> = ConcurrentHashMap()
    private val IN_FLIGHT_TITLES: ConcurrentMap<String, CompletableFuture<String?>> = ConcurrentHashMap()

    @Volatile private var resolvedBinary: String? = null
    @Volatile private var resolvedCookieBrowser: String? = null
    @Volatile private var cookieBrowserResolved: Boolean = false
    @Volatile private var cachedCookieHeader: String? = null
    @Volatile private var cookieHeaderExportedAt: Long = 0

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
            return future.get(180, TimeUnit.SECONDS)
        } catch (e: CompletionException) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp fetch failed for url: $videoUrl", e.cause ?: e)
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp fetch failed for url: $videoUrl", e.cause ?: e)
        } finally {
            IN_FLIGHT_FETCHES.remove(videoUrl, future)
        }
    }

    fun prewarmAsync() {
        if (resolvedBinary != null && cookieBrowserResolved && cachedCookieHeader != null) return
        PREWARM_EXECUTOR.submit {
            try {
                resolveBinary()
                resolveCookieBrowser()
                getCookieHeader()
            } catch (e: IOException) {
                LoggingManager.warn("Failed to prewarm yt-dlp", e)
            }
        }
    }

    fun prefetchFormats(videoUrl: String) {
        if (videoUrl.isBlank()) return
        val cached = FORMAT_CACHE[videoUrl]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= CACHE_TTL_MS) return
        startFetchInternal(videoUrl)
    }

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
                    val web = YouTubeWeb.search(query.trim(), n)
                    if (web.isNotEmpty()) {
                        val results = web.toList()
                        SEARCH_CACHE[key] = InfoCacheEntry(results, System.currentTimeMillis())
                        return@supplyAsync results
                    }
                } catch (webEx: Exception) {
                    LoggingManager.warn("[YouTubeWeb] search failed, falling back to yt-dlp: ${webEx.message}")
                }
                try {
                    val results = searchUncached(query.trim(), n).toList()
                    SEARCH_CACHE[key] = InfoCacheEntry(results, System.currentTimeMillis())
                    results
                } catch (e: IOException) {
                    throw CompletionException(e)
                }
            }, SEARCH_EXECUTOR)
        }
        return waitForInfoFuture(future, "search '$query'") { IN_FLIGHT_SEARCHES.remove(key, future) }
    }

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
                    val web = YouTubeWeb.related(videoId, n)
                    if (web.isNotEmpty()) {
                        val immutable = web.toList()
                        RELATED_CACHE[key] = InfoCacheEntry(immutable, System.currentTimeMillis())
                        return@supplyAsync immutable
                    }
                } catch (_: Exception) {
                }
                try {
                    loadRelatedUncached(videoId, n, key)
                } catch (e: IOException) {
                    throw CompletionException(e)
                }
            }, SEARCH_EXECUTOR)
        }
        return waitForInfoFuture(future, "related $videoId") { IN_FLIGHT_RELATED.remove(key, future) }
    }

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

    @Throws(IOException::class)
    private fun searchUncached(query: String, limit: Int): List<YtVideoInfo> {
        val binary = resolveBinary()
        val args = ArrayList<String>()
        args.add(binary)
        addCookieArgs(args)
        args.addAll(listOf("-J", "--flat-playlist", "--no-warnings",
            "--extractor-args", "youtube:player_client=web",
            "--playlist-end", limit.toString(),
            "ytsearch$limit:$query"))
        return parseEntries(runJsonProcess(ProcessBuilder(args), "search:$query", 25))
    }

    private fun addCookieArgs(args: MutableList<String>) {
        val proxy = Initializer.config.ytdlpProxy.trim()
        if (proxy.isNotEmpty()) {
            args.add("--proxy")
            args.add(proxy)
        }
        val cookieFile = BUNDLED_DIR.resolve("cookies.txt")
        if (Files.exists(cookieFile)) {
            try {
                val age = System.currentTimeMillis() - Files.getLastModifiedTime(cookieFile).toMillis()
                if (age < COOKIE_REFRESH_MS) {
                    args.add("--cookies")
                    args.add(cookieFile.toString())
                    return
                }
            } catch (_: IOException) {
            }
        }
        resolveCookieBrowser()?.let {
            args.add("--cookies-from-browser")
            args.add(it)
        }
    }

    @Throws(IOException::class)
    private fun loadRelatedUncached(videoId: String, limit: Int, cacheKey: String): List<YtVideoInfo> {
        val binary = resolveBinary()
        val args = ArrayList<String>()
        args.add(binary)
        addCookieArgs(args)
        args.addAll(listOf("-J", "--flat-playlist", "--no-warnings",
            "--extractor-args", "youtube:player_client=web",
            "--playlist-end", (limit + 2).toString(),
            "--lazy-playlist",
            "https://www.youtube.com/watch?v=$videoId&list=RD$videoId"))
        var hits: MutableList<YtVideoInfo>
        try {
            hits = ArrayList(parseEntries(runJsonProcess(ProcessBuilder(args), "mix:$videoId", 20)))
        } catch (_: IOException) {
            var title: String? = null
            try {
                YouTubeWeb.metadata(videoId)?.let { title = it.title }
            } catch (_: Exception) {
            }
            if (title.isNullOrBlank()) title = fetchVideoTitle(videoId)
            if (title.isNullOrBlank()) {
                RELATED_CACHE[cacheKey] = InfoCacheEntry(emptyList(), System.currentTimeMillis())
                return emptyList()
            }
            hits = ArrayList(search(title, limit + 2))
        }
        hits.removeAll { it.id == videoId }
        if (hits.size > limit) hits = hits.subList(0, limit)
        val immutable = hits.toList()
        RELATED_CACHE[cacheKey] = InfoCacheEntry(immutable, System.currentTimeMillis())
        return immutable
    }

    @Throws(IOException::class)
    private fun fetchVideoTitle(videoId: String): String? {
        val cached = TITLE_CACHE[videoId]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.createdAtMs) <= INFO_CACHE_TTL_MS) return cached.title

        val future = IN_FLIGHT_TITLES.computeIfAbsent(videoId) {
            CompletableFuture.supplyAsync({
                try {
                    val title = fetchVideoTitleUncached(videoId)
                    TITLE_CACHE[videoId] = TitleCacheEntry(title, System.currentTimeMillis())
                    title
                } catch (e: IOException) {
                    throw CompletionException(e)
                }
            }, SEARCH_EXECUTOR)
        }
        try {
            return future.get(30, TimeUnit.SECONDS)
        } catch (e: CompletionException) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp title fetch failed for video: $videoId", e.cause ?: e)
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp title fetch failed for video: $videoId", e.cause ?: e)
        } finally {
            IN_FLIGHT_TITLES.remove(videoId, future)
        }
    }

    @Throws(IOException::class)
    private fun fetchVideoTitleUncached(videoId: String): String? {
        val binary = resolveBinary()
        val args = ArrayList<String>()
        args.add(binary)
        addCookieArgs(args)
        args.addAll(listOf("--print", "%(title)s", "--no-warnings", "--skip-download",
            "https://www.youtube.com/watch?v=$videoId"))
        val out = runJsonProcess(ProcessBuilder(args), "title:$videoId", 25).trim()
        return out.ifEmpty { null }
    }

    @Throws(IOException::class)
    private fun runJsonProcess(pb: ProcessBuilder, tag: String, timeoutSec: Int): String {
        pb.redirectErrorStream(false)
        val process = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = streamReader(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = streamReader(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
                process.destroyForcibly()
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                throw IOException("yt-dlp timed out for $tag")
            }
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted waiting for yt-dlp", e)
        }
        if (process.exitValue() != 0) {
            throw IOException("yt-dlp exited ${process.exitValue()}: ${stderr.toString().trim()}")
        }
        return stdout.toString()
    }

    @Throws(IOException::class)
    private fun parseEntries(json: String): List<YtVideoInfo> {
        val out = ArrayList<YtVideoInfo>()
        try {
            val root = JsonParser.parseString(json).asJsonObject
            if (!root.has("entries") || !root.get("entries").isJsonArray) return out
            for (el in root.getAsJsonArray("entries")) {
                if (!el.isJsonObject) continue
                val e = el.asJsonObject
                val id = optString(e, "id") ?: continue
                val title = optString(e, "title") ?: continue
                val dur = optDouble(e, "duration")
                val durSec = dur?.let { floor(it).toLong() }
                val views = optLong(e)
                val uploader = optString(e, "uploader") ?: optString(e, "channel")
                out.add(YtVideoInfo(id, title, uploader, durSec, views))
            }
            return out
        } catch (e: Exception) {
            throw IOException("Failed to parse search JSON", e)
        }
    }

    private fun optLong(obj: JsonObject): Long? {
        if (!obj.has("view_count") || obj.get("view_count").isJsonNull) return null
        return try { obj.get("view_count").asLong } catch (_: Exception) { null }
    }

    @Throws(IOException::class)
    private fun waitForInfoFuture(
        future: CompletableFuture<List<YtVideoInfo>>,
        tag: String,
        cleanup: () -> Unit,
    ): List<YtVideoInfo> {
        try {
            return future.get(30, TimeUnit.SECONDS)
        } catch (e: CompletionException) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp $tag failed", e.cause ?: e)
        } catch (e: Exception) {
            throw (e.cause as? IOException) ?: IOException("yt-dlp $tag failed", e.cause ?: e)
        } finally {
            cleanup()
        }
    }

    @Throws(IOException::class)
    private fun fetchUncached(videoUrl: String): List<YtStream> {
        var lastError: IOException? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) {
                try { Thread.sleep(5_000) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted before yt-dlp retry", ie)
                }
            }
            try {
                return fetchUncachedOnce(videoUrl)
            } catch (e: IOException) {
                if (e.message?.contains("timed out") == true) throw e
                lastError = e
            }
        }
        throw lastError!!
    }

    @Throws(IOException::class)
    private fun fetchUncachedOnce(videoUrl: String): List<YtStream> {
        val binary = resolveBinary()
        val cmd = ArrayList<String>()
        cmd.add(binary)
        addCookieArgs(cmd)

        if (resolveCookieBrowser() == null && !Files.exists(BUNDLED_DIR.resolve("cookies.txt"))) {
            cmd.addAll(listOf("--extractor-args", "youtube:player_client=tv_embedded,web,mweb"))
        }

        cmd.addAll(listOf(
            "-J", "--no-playlist", "--no-warnings", "--no-check-formats",
            "--ignore-config", "--no-mark-watched",
            "--extractor-retries", "1",
            "--socket-timeout", "10",
            videoUrl,
        ))
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(false)
        val process = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = streamReader(process.inputStream, stdout, "YtDlp-stdout")
        val stderrReader = streamReader(process.errorStream, stderr, "YtDlp-stderr")
        stdoutReader.start()
        stderrReader.start()
        try {
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                stdoutReader.join(2_000)
                stderrReader.join(2_000)
                LoggingManager.warn("[YtDlp] fetch timeout after 90s for $videoUrl (stderr: ${stderr.toString().trim()})")
                throw IOException("yt-dlp timed out for url: $videoUrl")
            }
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for yt-dlp", e)
        }
        if (process.exitValue() != 0) {
            throw IOException("yt-dlp exited with code ${process.exitValue()}: ${stderr.toString().trim()}")
        }
        return parseFormats(stdout.toString())
    }

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

    fun invalidateCache(videoUrl: String) {
        FORMAT_CACHE.remove(videoUrl)
        IN_FLIGHT_FETCHES.remove(videoUrl)
        FormatDiskCache.deleteEntry(videoUrl)
    }

    fun getPublicCookieHeader(): String? = getCookieHeader()

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
                LoggingManager.warn("[YtDlp] cookie export failed: ${e.message}")
                cachedCookieHeader
            }
        }
    }

    @Throws(IOException::class)
    private fun exportCookieHeader(): String? {
        val browser = resolveCookieBrowser() ?: return null
        val binary = resolveBinary()
        val cookieFile = BUNDLED_DIR.resolve("cookies.txt")
        Files.createDirectories(cookieFile.parent)

        val pb = ProcessBuilder(binary,
            "--cookies-from-browser", browser,
            "--cookies", cookieFile.toString(),
            "--simulate", "--quiet", "--no-warnings",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.readAllBytes()
        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw IOException("cookie export timed out")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }

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
        LoggingManager.info("[YtDlp] exported ${lines.size} cookie lines, header ${result.length} chars")
        return result
    }

    internal fun extractJsonObject(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val braceStart = html.indexOf('{', idx)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val c = html[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return html.substring(braceStart, i + 1)
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun parseFormats(json: String): List<YtStream> {
        val result = ArrayList<YtStream>()
        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IOException("Failed to parse yt-dlp JSON output", e)
        }
        if (!root.isJsonObject) throw IOException("yt-dlp returned unexpected JSON shape")
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

            val resolution: String? = if (hasVideo) {
                val h = optInt(f)
                if (h != null && h > 0) "${h}p"
                else extractResolution(optString(f, "resolution"), optString(f, "format_note"), optString(f, "format"))
            } else null

            val language = optString(f, "language")
            val formatNote = optString(f, "format_note")
            val fps = optDouble(f, "fps")
            val tbr = optDouble(f, "tbr")

            result.add(YtStream(url, mime, container, protocol, resolution,
                language, formatNote, vcodec, acodec, fps, tbr,
                hasVideo, hasAudio, live, seekable, durationNanos))
        }
        return result
    }

    private fun isLive(obj: JsonObject): Boolean {
        if (optBoolean(obj)) return true
        val liveStatus = optString(obj, "live_status") ?: return false
        return when (liveStatus) {
            "is_live", "is_upcoming", "post_live" -> true
            else -> false
        }
    }

    private fun getDurationNanos(obj: JsonObject): Long {
        val durationSeconds = optDouble(obj, "duration") ?: return 0L
        if (durationSeconds <= 0.0) return 0L
        val nanos = durationSeconds * 1_000_000_000.0
        if (nanos >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return max(0L, round(nanos).toLong())
    }

    private fun isSupportedProtocol(protocol: String?, url: String): Boolean {
        if (protocol.isNullOrBlank()) return true
        if (protocol.startsWith("http")) return true
        if ("m3u8" in protocol) return true
        if ("dash" in protocol) return true
        val lowerUrl = url.lowercase(Locale.ENGLISH)
        return ".m3u8" in lowerUrl || ".mpd" in lowerUrl
    }

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

    private fun optString(obj: JsonObject, key: String): String? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try { obj.get(key).asString } catch (_: Exception) { null }
    }

    private fun optInt(obj: JsonObject): Int? {
        if (!obj.has("height") || obj.get("height").isJsonNull) return null
        return try { obj.get("height").asInt } catch (_: Exception) { null }
    }

    private fun optDouble(obj: JsonObject, key: String): Double? {
        if (!obj.has(key) || obj.get(key).isJsonNull) return null
        return try { obj.get(key).asDouble } catch (_: Exception) { null }
    }

    private fun optBoolean(obj: JsonObject): Boolean {
        if (!obj.has("is_live") || obj.get("is_live").isJsonNull) return false
        return try { obj.get("is_live").asBoolean } catch (_: Exception) { false }
    }

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

    private fun resolveCookieBrowser(): String? {
        if (cookieBrowserResolved) return resolvedCookieBrowser
        synchronized(this) {
            if (cookieBrowserResolved) return resolvedCookieBrowser
            var configured = Initializer.config.ytdlpCookiesFromBrowser
            configured = configured.trim().lowercase(Locale.ENGLISH)

            if (configured == "none" || configured == "off" || configured == "disabled" || configured.isEmpty()) {
                cookieBrowserResolved = true
                resolvedCookieBrowser = null
                LoggingManager.info("[YtDlp] cookies-from-browser disabled via config")
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
                    LoggingManager.info("[YtDlp] using cookies from browser: $browser")
                    resolvedCookieBrowser = browser
                    cookieBrowserResolved = true
                    return browser
                }
            }
            LoggingManager.warn("[YtDlp] no working browser cookies found (tried: ${candidates.joinToString(", ")}). " +
                "YouTube may rate-limit or refuse requests. " +
                "Set 'ytdlp-cookies-from-browser' in config to your browser name, " +
                "or to 'none' to silence this warning.")
            cookieBrowserResolved = true
            return null
        }
    }

    private fun testCookieBrowser(binary: String, browser: String): Boolean {
        return try {
            val testCookieFile = BUNDLED_DIR.resolve("cookie-test-$browser.txt")
            Files.createDirectories(testCookieFile.parent)
            Files.deleteIfExists(testCookieFile)

            val pb = ProcessBuilder(binary,
                "--cookies-from-browser", browser,
                "--cookies", testCookieFile.toString(),
                "--simulate", "--quiet", "--no-warnings", "--skip-download",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.readAllBytes()
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return false
            }
            if (!Files.exists(testCookieFile)) return false
            val lines = Files.readAllLines(testCookieFile, StandardCharsets.UTF_8)
            Files.deleteIfExists(testCookieFile)
            lines.any { !it.startsWith("#") && "youtube" in it }
        } catch (_: Exception) {
            false
        }
    }

    private fun bundledBinaryName(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        return if ("win" in os) "yt-dlp.exe" else "yt-dlp"
    }

    private fun downloadAssetName(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
        if ("win" in os) return "yt-dlp.exe"
        if ("mac" in os) return "yt-dlp_macos"
        if ("aarch64" in arch || "arm64" in arch) return "yt-dlp_linux_aarch64"
        if ("arm" in arch) return "yt-dlp_linux_armv7l"
        return "yt-dlp_linux"
    }

    @Throws(IOException::class)
    private fun downloadBundled(target: Path): String {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.part")
        val url = DOWNLOAD_BASE + downloadAssetName()
        LoggingManager.info("Downloading yt-dlp from $url")

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
                ProcessBuilder("xattr", "-d", "com.apple.quarantine", target.toString())
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }

        val path = target.toString()
        LoggingManager.info("yt-dlp ready at $path")
        return path
    }

    private fun canExecute(path: String): Boolean {
        return try {
            val f = File(path)
            if (f.isAbsolute || File.separator in path) return f.isFile && f.canExecute()
            val pb = ProcessBuilder(path, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.readAllBytes()
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
    private data class TitleCacheEntry(val title: String?, val createdAtMs: Long)
    private data class CacheEntry(val streams: List<YtStream>, val createdAtMs: Long)
}
