package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.OsInfo
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Provisioning of the `yt-dlp` executable: locating a system install, falling back to the bundled
 * copy, downloading from GitHub as a last resort, and weekly background self-updates of the bundled
 * binary.
 */
object YtDlpBinary {
    private val logger = LoggerFactory.getLogger(javaClass)

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
    private const val ZIPAPP_ASSET = "yt-dlp"
    private const val ZIPAPP_NAME = "yt-dlp.pyz"
    private const val MIN_PYTHON_MINOR = 9

    private val PYTHON_CANDIDATES: Array<String> =
        if (OsInfo.isWindows) arrayOf("python", "python3", "py")
        else arrayOf("python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3", "python")

    private const val BINARY_REFRESH_MS: Long = 1L * 24L * 60L * 60L * 1_000L

    @Volatile
    private var resolved: String? = null
    private val updateChecked = atomic(false)

    @Volatile
    private var resolvedCommand: List<String>? = null

    @Volatile
    private var pythonProbe: String? = null
    private val zipappUpdateChecked = atomic(false)

    /**
     * Returns the path to a usable `yt-dlp` binary, checking the `dreamdisplays.ytdlp` override, well-known system paths,
     * then falling back to the bundled copy.
     */
    @Throws(IOException::class)
    fun resolve(): String {
        resolved?.let { return it }
        synchronized(this) {
            resolved?.let { return it }

            val candidates = ArrayList<String>()
            override()?.let(candidates::add)
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
     * Returns the command prefix used to launch yt-dlp — `[python3, .../yt-dlp.pyz]` for the fast
     * zipapp path, or a single-element `[binaryPath]` for the PyInstaller binary. Callers append the
     * `yt-dlp` arguments to this list.
     */
    @Throws(IOException::class)
    fun resolveCommand(): List<String> {
        resolvedCommand?.let { return it }
        synchronized(this) {
            resolvedCommand?.let { return it }
            val zipapp = if (override() != null) null else zipappCommand()
            val command = zipapp ?: [resolve()]
            resolvedCommand = command
            return command
        }
    }

    private fun override(): String? =
        System.getProperty("dreamdisplays.ytdlp")?.ifEmpty { null } ?: System.getenv("DREAMDISPLAYS_YTDLP")?.ifEmpty { null }

    private fun zipappCommand(): List<String>? {
        val python = usablePython() ?: return null
        val pyz = provisionZipapp() ?: return null
        logger.info("yt-dlp fast path active: {} {}.", python, pyz.fileName)
        return [python, pyz.toString()]
    }

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

    private fun pythonVersionOk(exe: String): Boolean {
        return runCatching {
            val p = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            runCatching { p.outputStream.close() }
            val sink = StringBuilder()
            val reader = Processes.collector(p.inputStream, sink, "YtDlp-python-probe").also { it.start() }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                Processes.destroyTree(p)
                return false
            }
            reader.join(2_000)
            val out = sink.toString()
            if (p.exitValue() != 0) return false
            val m = Regex("""Python (\d+)\.(\d+)""").find(out) ?: return false
            val major = m.groupValues[1].toInt()
            val minor = m.groupValues[2].toInt()
            major > 3 || (major == 3 && minor >= MIN_PYTHON_MINOR)
        }.getOrDefault(false)
    }

    private fun provisionZipapp(): Path? {
        val pyz = bundledDir.resolve(ZIPAPP_NAME)
        if (Files.isRegularFile(pyz)) {
            maybeRefreshZipapp(pyz)
            return pyz
        }
        return runCatching {
            downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
            pyz
        }.onFailure { e ->
            logger.warn("yt-dlp zipapp download failed; falling back to the bundled binary: ${e.message}.")
        }.getOrNull()
    }

    private fun maybeRefreshZipapp(pyz: Path) {
        if (!zipappUpdateChecked.compareAndSet(expect = false, update = true)) return
        val age = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(pyz).toMillis()
        }.getOrNull() ?: return
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            runCatching {
                logger.debug("Bundled yt-dlp.pyz is ${age / 86_400_000L} days old, refreshing...")
                downloadAsset(pyz, ZIPAPP_ASSET, executable = false)
                logger.debug("yt-dlp.pyz refresh finished.")
            }.onFailure { e ->
                logger.warn("yt-dlp.pyz refresh failed: ${e.message}.")
            }
        }
    }

    private fun maybeSelfUpdate(bundled: Path) {
        if (!updateChecked.compareAndSet(expect = false, update = true)) return
        val age = runCatching {
            System.currentTimeMillis() - Files.getLastModifiedTime(bundled).toMillis()
        }.getOrNull() ?: return
        if (age < BINARY_REFRESH_MS) return
        DreamCoroutines.clientIo.launch {
            runCatching {
                logger.debug("Bundled yt-dlp is ${age / 86_400_000L} days old, running self-update...")
                val p = ProcessBuilder(bundled.toString(), "-U", "--no-warnings")
                    .redirectErrorStream(true).start()
                runCatching { p.outputStream.close() }
                Processes.drainAsync(p.inputStream)
                if (!p.waitFor(120, TimeUnit.SECONDS)) {
                    Processes.destroyTree(p)
                    return@launch
                }
                runCatching { Files.setLastModifiedTime(bundled, FileTime.fromMillis(System.currentTimeMillis())) }
                logger.debug("yt-dlp self-update finished.")
            }.onFailure { e ->
                logger.warn("yt-dlp self-update failed: ${e.message}")
            }
        }
    }

    private fun bundledBinaryName(): String = if (OsInfo.isWindows) "yt-dlp.exe" else "yt-dlp"

    private fun downloadAssetName(): String = when {
        OsInfo.isWindows -> "yt-dlp.exe"
        OsInfo.isMac -> "yt-dlp_macos"
        OsInfo.isArm64 -> "yt-dlp_linux_aarch64"
        OsInfo.isArm -> "yt-dlp_linux_armv7l"
        else -> "yt-dlp_linux"
    }

    @Throws(IOException::class)
    private fun download(target: Path): String {
        downloadAsset(target, downloadAssetName(), executable = true)
        logger.debug("Ready to work.")
        return target.toString()
    }

    @Throws(IOException::class)
    private fun downloadAsset(target: Path, asset: String, executable: Boolean) {
        SourceLoader.download(DOWNLOAD_BASE + asset, target, asset, readTimeoutMs = 120_000)
        if (executable && !OsInfo.isWindows) SourceLoader.makeExecutable(target)
    }

    private fun canExecute(path: String): Boolean {
        return runCatching {
            val f = File(path)
            if (f.isAbsolute || File.separator in path) return f.isFile && f.canExecute()
            val pb = ProcessBuilder(path, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            runCatching { p.outputStream.close() }
            Processes.drainAsync(p.inputStream)
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                Processes.destroyTree(p)
                return false
            }
            p.exitValue() == 0
        }.getOrDefault(false)
    }
}
