package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.media.runtime.Processes
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything cookie-related for YouTube access: resolving which browser to export from (opt-in via
 * config), materializing `cookies.txt` under a cross-process lock, periodic re-export, and the
 * parsed cookie header for plain HTTP clients (InnerTube).
 *
 * On any export failure the manager disables itself for the session ([unavailableThisSession])
 * instead of letting every fetch hang on a broken browser export. Background prewarm / refresh work
 * runs on [DreamCoroutines.clientIo].
 */
class YtCookieManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")

    private val cookieFile: Path get() = YtDlpBinary.bundledDir.resolve("cookies.txt")

    @Volatile
    private var resolvedBrowser: String? = null

    @Volatile
    private var browserResolved: Boolean = false

    @Volatile
    private var browserResolvedAt: Long = 0

    @Volatile
    private var cachedHeader: String? = null

    @Volatile
    private var headerExportedAt: Long = 0

    @Volatile
    private var unavailableThisSession = false

    private val refreshInProgress = AtomicBoolean(false)

    /** True when the user explicitly disabled browser cookies in the config. */
    fun disabledByConfig(): Boolean =
        ResolverConfig.ytdlpCookieSource.isDisabled

    /** Resolves the browser and exports the header in the background to cut first-fetch latency. */
    fun prewarm() {
        resolveBrowser()
        header()
    }

    /**
     * Appends proxy and cookie arguments to the `yt-dlp` command [args]. Prefers a materialized
     * cookies.txt (copied to a temp file so concurrent processes don't clobber each other); falls
     * back to `--cookies-from-browser`. Returns the temp copy to delete after the process exits.
     */
    fun appendArgs(args: MutableList<String>): Path? {
        val proxy = ResolverConfig.ytdlpProxy.trim()
        if (proxy.isNotEmpty()) {
            args.add("--proxy")
            args.add(proxy)
        }
        if (disabledByConfig()) return null

        val master = cookieFile
        if (!Files.exists(master) && !unavailableThisSession) ensureMaterialized(master)
        if (Files.exists(master)) {
            val tempCopy = try {
                val target = Files.createTempFile(YtDlpBinary.bundledDir, "cookies-", ".txt")
                Files.copy(master, target, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (e: IOException) {
                logger.warn("Failed to create temp cookies copy, using master file: ${e.message}")
                null
            }
            args.add("--cookies")
            args.add((tempCopy ?: master).toString())
            try {
                val age = System.currentTimeMillis() - Files.getLastModifiedTime(master).toMillis()
                if (age >= COOKIE_REFRESH_MS) refreshAsync()
            } catch (_: IOException) {
            }
            return tempCopy
        }
        if (!unavailableThisSession) {
            resolveBrowser()?.let {
                args.add("--cookies-from-browser")
                args.add(it)
            }
        }
        return null
    }

    /** Returns the cached cookie header for plain HTTP clients, re-exporting if the TTL has expired. */
    fun header(): String? {
        if (disabledByConfig()) return null
        val cached = cachedHeader
        val now = System.currentTimeMillis()
        if (cached != null && (now - headerExportedAt) < COOKIE_REFRESH_MS) return cached
        synchronized(this) {
            if (cachedHeader != null && (System.currentTimeMillis() - headerExportedAt) < COOKIE_REFRESH_MS) {
                return cachedHeader
            }
            return try {
                val header = exportHeader()
                cachedHeader = header
                headerExportedAt = System.currentTimeMillis()
                header
            } catch (e: Exception) {
                logger.warn("Cookie export failed: ${e.message}")
                cachedHeader
            }
        }
    }

    /**
     * Synchronously exports cookies to [master] under a cross-process file lock. No-op if another
     * process already created the file while we were waiting. Marks cookies unavailable for the
     * session when the export produces nothing.
     */
    private fun ensureMaterialized(master: Path) {
        if (resolveBrowser() == null) return
        try {
            Files.createDirectories(EXPORT_LOCK_FILE.parent)
            RandomAccessFile(EXPORT_LOCK_FILE.toFile(), "rw").use { raf ->
                raf.channel.lock().use {
                    if (Files.exists(master)) return
                    try {
                        exportHeader()
                    } catch (e: Exception) {
                        logger.warn("Synchronous cookie export failed: ${e.message}")
                    }
                    if (!Files.exists(master)) {
                        unavailableThisSession = true
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

    /** Re-exports the browser cookie file in the background if not already in progress. */
    private fun refreshAsync() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        DreamCoroutines.clientIo.launch {
            try {
                exportHeader()
            } catch (_: Exception) {
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    /** Runs `yt-dlp --cookies-from-browser` to write cookies.txt, then parses relevant YouTube / Google cookie lines into a header string. */
    @Throws(IOException::class)
    private fun exportHeader(): String? {
        val browser = resolveBrowser() ?: return null
        val binary = YtDlpBinary.resolve()
        val master = cookieFile
        Files.createDirectories(master.parent)

        val pb = ProcessBuilder(
            binary,
            "--cookies-from-browser", browser,
            "--cookies", master.toString(),
            "--simulate", "--quiet", "--no-warnings",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        val drainer = Processes.drainAsync(p.inputStream)
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                Processes.destroyTree(p)
                unavailableThisSession = true
                throw IOException("Cookie export timed out.")
            }
        } catch (e: InterruptedException) {
            Processes.destroyTree(p)
            Thread.currentThread().interrupt()
            throw IOException("Interrupted", e)
        }
        try {
            drainer.join(500)
        } catch (_: InterruptedException) {
        }

        if (!Files.exists(master)) return null
        val lines = Files.readAllLines(master, StandardCharsets.UTF_8)
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
        logger.debug("Exported ${lines.size} cookie lines, header ${result.length} chars.")
        return result
    }

    /**
     * Resolves the browser to use for `--cookies-from-browser`, respecting the config setting.
     * The result is cached; null means cookies are disabled or no suitable browser was found.
     */
    private fun resolveBrowser(): String? {
        val now = System.currentTimeMillis()
        if (browserResolved && resolvedBrowser != null) return resolvedBrowser
        if (browserResolved && (now - browserResolvedAt) < BROWSER_RETRY_MS) return null
        synchronized(this) {
            if (browserResolved && resolvedBrowser != null) return resolvedBrowser
            if (browserResolved && (now - browserResolvedAt) < BROWSER_RETRY_MS) return null
            val cookieSource = ResolverConfig.ytdlpCookieSource

            if (disabledByConfig()) {
                browserResolved = true
                resolvedBrowser = null
                // Set the timestamp so the early-return guards above short-circuit later calls;
                // otherwise this branch re-runs (and re-logs) on every fetch.
                browserResolvedAt = System.currentTimeMillis()
                logger.debug("Cookies-from-browser disabled via config.")
                return null
            }

            // Cookies are opt-in, so "configured" is always a single explicit browser name here
            // (see disabledByConfig). No auto-detection sweep -> no macOS keychain popup.
            val binary: String? = try {
                YtDlpBinary.resolve()
            } catch (_: IOException) {
                browserResolved = true
                return null
            }
            if (binary == null) {
                browserResolved = true
                return null
            }
            val browser = cookieSource.browserName ?: return null
            if (testBrowser(binary, browser)) {
                logger.info("Using cookies from browser: $browser...")
                resolvedBrowser = browser
                browserResolved = true
                browserResolvedAt = System.currentTimeMillis()
                return browser
            }
            logger.warn(
                "Browser '$browser' not found or has no YouTube cookies. " +
                        "YouTube may rate-limit or refuse requests. " +
                        "Set 'ytdlp-cookies-from-browser' to 'none' to disable."
            )
            browserResolvedAt = System.currentTimeMillis()
            browserResolved = true
            return null
        }
    }

    /** Runs `yt-dlp --dump-user-agent` with [browser] cookies and checks whether the cookie file was produced. */
    private fun testBrowser(binary: String, browser: String): Boolean {
        return try {
            val safeName = browser.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val testFile = YtDlpBinary.bundledDir.resolve("cookie-test-$safeName.txt")
            Files.createDirectories(testFile.parent)
            Files.deleteIfExists(testFile)

            val pb = ProcessBuilder(
                binary,
                "--cookies-from-browser", browser,
                "--cookies", testFile.toString(),
                "--dump-user-agent", "--quiet", "--no-warnings",
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            try {
                p.outputStream.close()
            } catch (_: IOException) {
            }
            val drainer = Processes.drainAsync(p.inputStream)
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                Processes.destroyTree(p)
                try {
                    drainer.join(500)
                } catch (_: InterruptedException) {
                }
                return false
            }
            try {
                drainer.join(500)
            } catch (_: InterruptedException) {
            }
            if (!Files.exists(testFile)) {
                return p.exitValue() == 0
            }
            val lines = Files.readAllLines(testFile, StandardCharsets.UTF_8)
            Files.deleteIfExists(testFile)
            lines.any { !it.startsWith("#") && it.count { c -> c == '\t' } >= 6 }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val COOKIE_REFRESH_MS: Long = 2L * 60L * 60L * 1_000L
        private const val BROWSER_RETRY_MS: Long = 60L * 60L * 1_000L

        /**
         * Cross-process lock for serializing `--cookies-from-browser` access across multiple game
         * installs on the same machine. Located in the system temp dir so all installs see the same
         * lock file.
         */
        private val EXPORT_LOCK_FILE: Path =
            Path.of(System.getProperty("java.io.tmpdir"), "dreamdisplays-yt-cookies.lock")
    }
}
