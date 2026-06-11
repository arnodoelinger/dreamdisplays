package com.dreamdisplays.ytdlp

import com.dreamdisplays.utils.OsInfo
import com.dreamdisplays.utils.Processes
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provisioning of the `yt-dlp` executable: locating a system install, falling back to the bundled
 * copy, downloading from GitHub as a last resort, and weekly background self-updates of the bundled
 * binary. Extracted from the YtDlp god-object so the orchestrator only deals in stream fetching.
 *
 * @since 1.6.0
 */
object YtDlpBinary {
    private val logger = LoggerFactory.getLogger("DreamDisplays/yt-dlp")

    /** Directory holding the bundled binary and its cookie files; shared with [YtCookieManager]. */
    val bundledDir: Path = Path.of("libs", "yt-dlp")

    private val CANDIDATE_PATHS = arrayOf(
        "yt-dlp",
        "/opt/homebrew/bin/yt-dlp",
        "/usr/local/bin/yt-dlp",
        "/usr/bin/yt-dlp",
        "C:\\Program Files\\yt-dlp\\yt-dlp.exe",
    )
    private const val DOWNLOAD_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"

    /** Re-run `yt-dlp -U` on the bundled binary once it is older than this; a stale binary is a top
     *  cause of "not a bot" / extraction failures since it never self-updates otherwise. */
    private const val BINARY_REFRESH_MS: Long = 7L * 24L * 60L * 60L * 1_000L

    @Volatile private var resolved: String? = null
    private val updateChecked = AtomicBoolean(false)

    /**
     * Returns the path to a usable `yt-dlp` binary, checking the `dreamdisplays.ytdlp` override,
     * well-known system paths, then the bundled directory, and downloading from GitHub as a last
     * resort. The result is cached for the session.
     *
     * @param selfUpdateExecutor where the background self-update runs when the bundled copy is used.
     */
    @Throws(IOException::class)
    fun resolve(selfUpdateExecutor: Executor): String {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }

            val candidates = ArrayList<String>()
            var override = System.getProperty("dreamdisplays.ytdlp")
            if (override.isNullOrEmpty()) override = System.getenv("DREAMDISPLAYS_YTDLP")
            if (!override.isNullOrEmpty()) candidates.add(override)

            candidates.addAll(CANDIDATE_PATHS)
            val bundled = bundledDir.resolve(bundledBinaryName())
            candidates.add(bundled.toString())

            for (c in candidates) {
                if (canExecute(c)) {
                    resolved = c
                    if (c == bundled.toString()) maybeSelfUpdate(bundled, selfUpdateExecutor)
                    return c
                }
            }

            val downloaded = download(bundled)
            resolved = downloaded
            return downloaded
        }
    }

    /**
     * If the bundled binary at [bundled] is older than [BINARY_REFRESH_MS], runs `yt-dlp -U` on
     * [executor] to pull the latest release. Runs at most once per session and never blocks the
     * caller; failures are logged and ignored. The binary's mtime is bumped on a successful run so a
     * no-op update doesn't re-check for another week.
     */
    private fun maybeSelfUpdate(bundled: Path, executor: Executor) {
        if (!updateChecked.compareAndSet(false, true)) return
        val age = try {
            System.currentTimeMillis() - Files.getLastModifiedTime(bundled).toMillis()
        } catch (_: IOException) {
            return
        }
        if (age < BINARY_REFRESH_MS) return
        executor.execute {
            try {
                logger.info("Bundled yt-dlp is ${age / 86_400_000L} days old, running self-update...")
                val p = ProcessBuilder(bundled.toString(), "-U", "--no-warnings")
                    .redirectErrorStream(true).start()
                try {
                    p.outputStream.close()
                } catch (_: IOException) {
                }
                p.inputStream.use { it.readAllBytes() }
                if (!p.waitFor(120, TimeUnit.SECONDS)) {
                    Processes.destroyTree(p)
                    return@execute
                }
                try {
                    Files.setLastModifiedTime(bundled, FileTime.fromMillis(System.currentTimeMillis()))
                } catch (_: IOException) {
                }
                logger.info("yt-dlp self-update finished.")
            } catch (e: Exception) {
                logger.warn("yt-dlp self-update failed: ${e.message}")
            }
        }
    }

    /** Returns the expected filename of the bundled binary for the current OS (e.g. `yt-dlp.exe` on Windows). */
    private fun bundledBinaryName(): String = if (OsInfo.isWindows) "yt-dlp.exe" else "yt-dlp"

    /** Returns the GitHub release asset name to download for the current OS and architecture. */
    private fun downloadAssetName(): String = when {
        OsInfo.isWindows -> "yt-dlp.exe"
        OsInfo.isMac -> "yt-dlp_macos"
        OsInfo.isArm64 -> "yt-dlp_linux_aarch64"
        OsInfo.isArm -> "yt-dlp_linux_armv7l"
        else -> "yt-dlp_linux"
    }

    /** Downloads `yt-dlp` from GitHub to [target], marks it executable, and removes the macOS quarantine flag. */
    @Throws(IOException::class)
    private fun download(target: Path): String {
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

        if (!OsInfo.isWindows) {
            Processes.markExecutable(target)
            Processes.removeMacQuarantine(target)
        }

        logger.info("Ready to work.")
        return target.toString()
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
}
