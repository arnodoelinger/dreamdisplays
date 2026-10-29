package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** `FFmpeg` binary downloader. **/
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"
    private const val FFMPEG_VERSION = "9.0"

    @Volatile
    private var cachedPath: String? = null

    /** Returns the path to a usable `FFmpeg` binary, resolving and caching it on the first call. */
    fun getPath(): String? {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            cachedPath = resolve()
            return cachedPath
        }
    }

    /**
     * Resolves the `FFmpeg` binary in the background to minimize latency on first use, and probes
     * its optional filters while it is there — otherwise that probe spawns its own `ffmpeg -filters`
     * synchronously inside the first playback launch, right where latency is most visible.
     */
    fun prewarmAsync() {
        daemon({
            runCatching {
                val path = getPath()
                if (path != null) FFmpegCapabilities.hasFilter(path, "scale_vt")
            }.onFailure { e -> logger.warn("Prewarm failed", e) }
        }, "FFmpeg-prewarm").start()
    }

    private fun resolve(): String? {
        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}-$FFMPEG_VERSION")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            logger.info("Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return runCatching {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("Extracted binary is missing or empty.")
            }
            Processes.markExecutable(binary.toPath())
            Processes.removeMacQuarantine(binary.toPath())
            deleteStaleBuilds(p.key)
            logger.info("Ready to work.")
            binary.absolutePath
        }.getOrElse { e ->
            logger.error("Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    private fun deleteStaleBuilds(platformKey: String) {
        val current = "$platformKey-$FFMPEG_VERSION"
        File(CACHE_ROOT).listFiles()
            ?.filter { it.isDirectory && it.name != current && (it.name == platformKey || it.name.startsWith("$platformKey-")) }
            ?.forEach { stale ->
                if (stale.deleteRecursively()) logger.info("Removed stale FFmpeg build: ${stale.name}.")
            }
    }

    @Throws(IOException::class)
    private fun downloadAndExtract(p: Platform, destBinary: File) {
        logger.info("Downloading ${p.url}...")
        val parent = destBinary.parentFile
        val tempArchive = File(parent, "_download" + if (p.isTarXz) ".tar.xz" else ".zip")
        try {
            downloadWithRedirects(p.url, tempArchive)
            logger.info("Downloaded ${tempArchive.length()} bytes, extracting '${p.entrySuffix}'...")
            if (p.isTarXz) extractFromTarXz(tempArchive, p.entrySuffix, destBinary)
            else extractFromZip(tempArchive, p.entrySuffix, destBinary)
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) tempArchive.deleteOnExit()
        }
    }

    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        DreamHttpClient.downloadToFile(
            url,
            dest.toPath(),
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-ffmpeg-bootstrap"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 300_000,
            ),
        )
    }

    @Throws(IOException::class)
    private fun extractFromZip(archive: File, suffix: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && !e.name.startsWith("__MACOSX/") && e.name.endsWith(suffix)) {
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> zis.transferTo(out) }
                    return
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    @Throws(IOException::class)
    private fun extractFromTarXz(archive: File, suffix: String, dest: File) {
        BufferedInputStream(FileInputStream(archive)).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(suffix)) {
                            BufferedOutputStream(FileOutputStream(dest)).use { out -> tar.transferTo(out) }
                            return
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    private fun findSystemFfmpeg(): String? {
        val candidates = arrayOf("ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        for (candidate in candidates) {
            try {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                daemon({
                    try {
                        p.inputStream.transferTo(OutputStream.nullOutputStream())
                    } catch (_: Exception) {
                    }
                }, "FFmpeg-version-drain").start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    logger.info("Using system ffmpeg: $candidate...")
                    return candidate
                }
                p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        logger.error("FFmpeg not found (no download succeeded, no system binary).")
        return null
    }

    private fun detectPlatform(): Platform? {
        val isArm = OsInfo.isArm
        return when {
            OsInfo.isWindows -> if (isArm) null else
                Platform(
                    "windows-x64",
                    "$BTBN_BASE/ffmpeg-n$FFMPEG_VERSION-latest-win64-gpl-$FFMPEG_VERSION.zip",
                    "ffmpeg.exe",
                    "/bin/ffmpeg.exe",
                    false
                )

            OsInfo.isMac -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg9arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/ffmpeg-9.0.2.zip", "ffmpeg", "ffmpeg", false)

            else -> if (isArm)
                Platform(
                    "linux-aarch64",
                    "$BTBN_BASE/ffmpeg-n$FFMPEG_VERSION-latest-linuxarm64-gpl-$FFMPEG_VERSION.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
            else
                Platform(
                    "linux-x64",
                    "$BTBN_BASE/ffmpeg-n$FFMPEG_VERSION-latest-linux64-gpl-$FFMPEG_VERSION.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
        }
    }

    private data class Platform(
        val key: String,
        val url: String,
        val binaryName: String,
        val entrySuffix: String,
        val isTarXz: Boolean,
    )
}
