package com.dreamdisplays.ffmpeg

import me.inotsleep.utils.logging.LoggingManager
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** FFmpeg binary downloader. **/
object FFmpegBinary {
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"

    @Volatile private var cachedPath: String? = null

    fun getPath(): String? {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            cachedPath = resolve()
            return cachedPath
        }
    }

    fun prewarmAsync() {
        Thread({
            try {
                getPath()
            } catch (e: Exception) {
                LoggingManager.warn("[FFmpeg] prewarm failed", e)
            }
        }, "Ffmpeg-prewarm").apply { isDaemon = true }.start()
    }

    private fun resolve(): String? {
        val p = detectPlatform() ?: run {
            LoggingManager.warn("[FFmpeg] No bundled binary URL for this OS / arch; trying system ffmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            LoggingManager.info("[FFmpeg] Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return try {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("[FFmpeg] Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("[FFmpeg] Extracted binary is missing or empty.")
            }
            markExecutable(binary)
            removeMacQuarantine(binary)
            LoggingManager.info("[FFmpeg] Ready to work.")
            binary.absolutePath
        } catch (e: Exception) {
            LoggingManager.error("[FFmpeg] Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    @Throws(IOException::class)
    private fun downloadAndExtract(p: Platform, destBinary: File) {
        LoggingManager.info("[FFmpeg] Downloading ${p.url}...")
        val parent = destBinary.parentFile
        val tempArchive = File(parent, "_download" + if (p.isTarXz) ".tar.xz" else ".zip")
        try {
            downloadWithRedirects(p.url, tempArchive)
            LoggingManager.info("[Ffmpeg] Downloaded ${tempArchive.length()} bytes, extracting '${p.entrySuffix}'...")
            if (p.isTarXz) extractFromTarXz(tempArchive, p.entrySuffix, destBinary)
            else extractFromZip(tempArchive, p.entrySuffix, destBinary)
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) tempArchive.deleteOnExit()
        }
    }

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
                if (loc == null) throw IOException("[FFmpeg] Redirect without Location at $currentUrl.")
                currentUrl = loc
                continue
            }
            if (status != 200) {
                conn.disconnect()
                throw IOException("[FFmpeg] HTTP $status for $currentUrl.")
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
        throw IOException("[FFmpeg] Too many redirects: $url.")
    }

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
        throw IOException("[FFmpeg] '$suffix' not found in ${archive.name}.")
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
        throw IOException("[FFmpeg] '$suffix' not found in ${archive.name}")
    }

    private fun markExecutable(binary: File) {
        try {
            Files.setPosixFilePermissions(binary.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        } catch (_: UnsupportedOperationException) {
            binary.setExecutable(true, false)
        } catch (_: IOException) {
            binary.setExecutable(true, false)
        }
    }

    // Hack: this operation is needed for safe FFmpeg execution on macOS, since otherwise the quarantine flag may prevent
    // it from running. It doesn't matter if this fails, since the binary will still work in most cases, but removing the
    // quarantine flag can help avoid some weird issues on macOS where the OS prevents the binary from running due to
    // security concerns.
    private fun removeMacQuarantine(binary: File) {
        if (!isMac()) return
        try {
            ProcessBuilder("xattr", "-d", "com.apple.quarantine", binary.absolutePath)
                .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    private fun findSystemFfmpeg(): String? {
        val candidates = arrayOf("ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        for (candidate in candidates) {
            try {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                Thread {
                    try {
                        p.inputStream.transferTo(OutputStream.nullOutputStream())
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    LoggingManager.info("[FFmpeg] Using system ffmpeg: $candidate.")
                    return candidate
                }
                p.destroyForcibly()
            } catch (_: Exception) {
            }
        }
        LoggingManager.error("[FFmpeg] FFmpeg not found (no download succeeded, no system binary).")
        return null
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name", "").lowercase(Locale.ENGLISH).contains("mac")

    private fun detectPlatform(): Platform? {
        val os = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
        val arch = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
        val isArm = "aarch64" in arch || "arm64" in arch || arch == "arm"
        return when {
            "win" in os -> if (isArm) null else
                Platform("windows-x64", "$BTBN_BASE/ffmpeg-master-latest-win64-gpl.zip", "ffmpeg.exe", "/bin/ffmpeg.exe", false)
            "mac" in os -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg71arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/getrelease/zip", "ffmpeg", "ffmpeg", false)
            else -> if (isArm)
                Platform("linux-aarch64", "$BTBN_BASE/ffmpeg-master-latest-linuxarm64-gpl.tar.xz", "ffmpeg", "/bin/ffmpeg", true)
            else
                Platform("linux-x64", "$BTBN_BASE/ffmpeg-master-latest-linux64-gpl.tar.xz", "ffmpeg", "/bin/ffmpeg", true)
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
