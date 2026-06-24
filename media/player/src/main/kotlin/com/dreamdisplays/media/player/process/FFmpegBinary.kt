package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.runtime.OsInfo
import com.dreamdisplays.media.runtime.Processes
import com.dreamdisplays.media.player.util.daemon
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** `FFmpeg` binary downloader. **/
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FFmpeg")
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"

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

    /** Resolves the `FFmpeg` binary in the background to minimize latency on first use. */
    fun prewarmAsync() {
        daemon({
            try {
                getPath()
            } catch (e: Exception) {
                logger.warn("Prewarm failed", e)
            }
        }, "FFmpeg-prewarm").start()
    }

    /**
     * Checks the cache directory for an existing binary, downloads and extracts one if not found,
     * and falls back to the system `FFmpeg` on any failure.
     */
    private fun resolve(): String? {
        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            logger.info("Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("Extracted binary is missing or empty.")
            }
            Processes.markExecutable(binary.toPath())
            Processes.removeMacQuarantine(binary.toPath())
            logger.info("Ready to work.")
            binary.absolutePath
        } catch (e: Exception) {
            logger.error("Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    /** Downloads the archive for [p] to a temp file, extracts the binary to [destBinary], and cleans up the temp file. */
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

    /** Downloads [url] to [dest], following up to 10 HTTP redirects manually (GitHub releases use multiple hops). */
    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        var currentUrl = url
        for (hops in 0 until 10) {
            val conn = URI.create(currentUrl).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "DreamDisplays-ffmpeg-bootstrap")
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            val status = conn.responseCode
            if (status in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw IOException("Redirect without Location at $currentUrl.")
                currentUrl = loc
                continue
            }
            if (status != 200) {
                conn.disconnect()
                throw IOException("HTTP $status for $currentUrl.")
            }
            try {
                conn.inputStream.use { input ->
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> input.transferTo(out) }
                }
            } finally {
                conn.disconnect()
            }
            return
        }
        throw IOException("Too many redirects: $url.")
    }

    /** Extracts the first ZIP entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromZip(archive: File, suffix: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(suffix)) {
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> zis.transferTo(out) }
                    return
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Extracts the first tar.xz entry whose name ends with [suffix] from [archive] to [dest]. */
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

    /** Scans well-known system paths for a working `ffmpeg` binary; returns null if none responds with exit 0. */
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

    /** Returns a [Platform] descriptor for the current OS and architecture, or null if no bundled build is available. */
    private fun detectPlatform(): Platform? {
        val isArm = OsInfo.isArm
        return when {
            OsInfo.isWindows -> if (isArm) null else
                Platform(
                    "windows-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-win64-gpl.zip",
                    "ffmpeg.exe",
                    "/bin/ffmpeg.exe",
                    false
                )

            OsInfo.isMac -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg71arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/getrelease/zip", "ffmpeg", "ffmpeg", false)

            else -> if (isArm)
                Platform(
                    "linux-aarch64",
                    "$BTBN_BASE/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
            else
                Platform(
                    "linux-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-linux64-gpl.tar.xz",
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
