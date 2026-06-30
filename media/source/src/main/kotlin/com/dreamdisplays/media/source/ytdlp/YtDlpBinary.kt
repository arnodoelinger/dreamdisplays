package com.dreamdisplays.media.source.ytdlp

import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.media.runtime.OsInfo
import com.dreamdisplays.media.runtime.Processes
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provisioning of the `yt-dlp` executable: locating a system install, falling back to the bundled
 * copy, downloading from GitHub as a last resort, and weekly background self-updates of the bundled
 * binary. Extracted from the YtDlp god-object so the orchestrator only deals in stream fetching.
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

    /** GitHub asset name of the lightweight Python zipapp variant, and its local filename. */
    private const val ZIPAPP_ASSET = "yt-dlp"
    private const val ZIPAPP_NAME = "yt-dlp.pyz"

    /** `yt-dlp` requires CPython >= 3.9; older interpreters are rejected so the zipapp never runs on one. */
    private const val MIN_PYTHON_MINOR = 9

    /** Interpreters probed for the zipapp fast path, in preference order (version-checked before use). */
    private val PYTHON_CANDIDATES: Array<String> =
        if (OsInfo.isWindows) arrayOf("python", "python3", "py")
        else arrayOf("python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3", "python")

    /** Re-run `yt-dlp -U` on the bundled binary once it is older than this; a stale binary is a top
     *  cause of "not a bot" / extraction failures since it never self-updates otherwise. */
    private const val BINARY_REFRESH_MS: Long = 7L * 24L * 60L * 60L * 1_000L

    @Volatile
    private var resolved: String? = null

    private val updateChecked = AtomicBoolean(false)

    /** Cached full launch prefix (e.g. `[python3, …/yt-dlp.pyz]` or `[binaryPath]`); see [resolveCommand]. */
    @Volatile
    private var resolvedCommand: List<String>? = null

    /** Probed interpreter for the zipapp path: `null` = not yet probed, `""` = none usable. */
    @Volatile
    private var pythonProbe: String? = null

    private val zipappUpdateChecked = AtomicBoolean(false)

    /**
     * Returns the path to a usable `yt-dlp` binary, checking the `dreamdisplays.ytdlp` override,
     * well-known system paths, then the bundled directory, and downloading from GitHub as a last
     * resort. The result is cached for the session. The background self-update of a bundled copy
     * runs on [DreamCoroutines.clientIo].
     */
    @Throws(IOException::class)
    fun resolve(): String {
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
                    if (c == bundled.toString()) maybeSelfUpdate(bundled)
                    return c
                }
            }

            val downloaded = download(bundled)
            resolved = downloaded
            return downloaded
        }
    }

    /**
     * Returns the command prefix used to launch yt-dlp — `[python3, …/yt-dlp.pyz]` for the fast
     * zipapp path, or a single-element `[binaryPath]` for the PyInstaller binary. Callers append the
     * `yt-dlp` arguments to this list.
     */
    @Throws(IOException::class)
    fun resolveCommand(): List<String> {
        resolvedCommand?.let { return it }
        synchronized(this) {
            resolvedCommand?.let { return it }
            val zipapp = if (hasOverride()) null else zipappCommand()
            val command = zipapp ?: listOf(resolve())
            resolvedCommand = command
            return command
        }
    }

    /** True when the user pinned a specific binary via `-Ddreamdisplays.ytdlp` / `DREAMDISPLAYS_YTDLP`. */
    private fun hasOverride(): Boolean {
        var o = System.getProperty("dreamdisplays.ytdlp")
        if (o.isNullOrEmpty()) o = System.getenv("DREAMDISPLAYS_YTDLP")
        return !o.isNullOrEmpty()
    }

    /** Builds the `[python, zipapp]` launch prefix, or null when no interpreter / zipapp is available. */
    private fun zipappCommand(): List<String>? {
        val python = usablePython() ?: return null
        val pyz = provisionZipapp() ?: return null
        logger.info("yt-dlp fast path active: {} {}.", python, pyz.fileName)
        return listOf(python, pyz.toString())
    }

    /** Returns the first CPython >= 3.[MIN_PYTHON_MINOR] from [PYTHON_CANDIDATES], cached; null if none. */
    private fun usablePython(): String? {
        pythonProbe?.let { return it.ifEmpty { null } }
        for (candidate in PYTHON_CANDIDATES) {
            if (pythonVersionOk(candidate)) {
                pythonProbe = candidate
                return candidate
            }
        }
        pythonProbe = ""
        return null
    }

    /** Runs `<exe> --version` and returns true when it reports CPython >= 3.[MIN_PYTHON_MINOR]. */
    private fun pythonVersionOk(exe: String): Boolean {
        return try {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            try {
                p.outputStream.close()
            } catch (_: IOException) {
            }
            val out = p.inputStream.use { String(it.readAllBytes()) }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return false
            }
            if (p.exitValue() != 0) return false
            val m = Regex("""Python (\d+)\.(\d+)""").find(out) ?: return false
            val major = m.groupValues[1].toInt()
            val minor = m.groupValues[2].toInt()
            major > 3 || (major == 3 && minor >= MIN_PYTHON_MINOR)
        } catch (_: Exception) {
            false
        }
    }

    /** Returns the local zipapp path, provisioning it (existing copy or GitHub download) on first use; null on failure. */
    private fun provisionZipapp(): Path? {
        val pyz = bundledDir.resolve(ZIPAPP_NAME)
        if (Files.isRegularFile(pyz)) {
            maybeRefreshZipapp(pyz)
            return pyz
        }
        return try {
            downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
            pyz
        } catch (e: IOException) {
            logger.warn("yt-dlp zipapp download failed; falling back to the bundled binary: ${e.message}.")
            null
        }
    }

    /**
     * Re-downloads the zipapp once per session when it is older than [BINARY_REFRESH_MS]. A stale
     * yt-dlp is a top cause of extraction failures; the cheap ~3 MB re-download keeps it current
     * (the binary path has its own `-U` in [maybeSelfUpdate]). Runs in the background, never blocking.
     */
    private fun maybeRefreshZipapp(pyz: Path) {
        if (!zipappUpdateChecked.compareAndSet(false, true)) return
        val age = try {
            System.currentTimeMillis() - Files.getLastModifiedTime(pyz).toMillis()
        } catch (_: IOException) {
            return
        }
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            try {
                logger.debug("Bundled yt-dlp.pyz is ${age / 86_400_000L} days old, refreshing...")
                downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
                logger.debug("yt-dlp.pyz refresh finished.")
            } catch (e: Exception) {
                logger.warn("yt-dlp.pyz refresh failed: ${e.message}.")
            }
        }
    }

    /**
     * If the bundled binary at [bundled] is older than [BINARY_REFRESH_MS], runs `yt-dlp -U` on
     * [executor] to pull the latest release. Runs at most once per session and never blocks the
     * caller; failures are logged and ignored. The binary's mtime is bumped on a successful run so a
     * no-op update doesn't re-check for another week.
     */
    private fun maybeSelfUpdate(bundled: Path) {
        if (!updateChecked.compareAndSet(false, true)) return
        val age = try {
            System.currentTimeMillis() - Files.getLastModifiedTime(bundled).toMillis()
        } catch (_: IOException) {
            return
        }
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            try {
                logger.debug("Bundled yt-dlp is ${age / 86_400_000L} days old, running self-update...")
                val p = ProcessBuilder(bundled.toString(), "-U", "--no-warnings")
                    .redirectErrorStream(true).start()
                try {
                    p.outputStream.close()
                } catch (_: IOException) {
                }
                p.inputStream.use { it.readAllBytes() }
                if (!p.waitFor(120, TimeUnit.SECONDS)) {
                    Processes.destroyTree(p)
                    return@launch
                }
                try {
                    Files.setLastModifiedTime(bundled, FileTime.fromMillis(System.currentTimeMillis()))
                } catch (_: IOException) {
                }
                logger.debug("yt-dlp self-update finished.")
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

    /** Downloads the OS-appropriate `yt-dlp` binary from GitHub to [target] and returns its path. */
    @Throws(IOException::class)
    private fun download(target: Path): String {
        downloadAsset(target, downloadAssetName(), executable = true)
        logger.debug("Ready to work.")
        return target.toString()
    }

    /**
     * Downloads release [asset] from GitHub to [target] via a temp file + atomic move. When
     * [executable] is true (native binaries) the file is marked runnable and de-quarantined on
     * macOS; the zipapp is fetched with it false since it is run through the Python interpreter.
     */
    @Throws(IOException::class)
    private fun downloadAsset(target: Path, asset: String, executable: Boolean) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.part")
        val url = DOWNLOAD_BASE + asset
        logger.debug("Downloading $asset from $url...")

        DreamHttpClient.downloadToFile(
            url,
            tmp,
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-yt-dlp-bootstrap"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 120_000,
            ),
        )

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        if (executable && !OsInfo.isWindows) {
            Processes.markExecutable(target)
            Processes.removeMacQuarantine(target)
        }
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
