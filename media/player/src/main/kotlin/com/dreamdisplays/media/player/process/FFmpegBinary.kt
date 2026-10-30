package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

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
            SourceLoader.downloadAndExtract(p.url, cacheDir.toPath(), "FFmpeg") { name ->
                p.binaryName.takeIf { name.endsWith(p.entrySuffix) }
            }
            if (!binary.isFile || binary.length() == 0L) throw IOException("Extracted binary is missing or empty.")
            SourceLoader.makeExecutable(binary.toPath())
            deleteStaleBuilds(p.key)
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

    private fun findSystemFfmpeg(): String? {
        val candidates = ["ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"]
        for (candidate in candidates) {
            runCatching {
                val p = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
                daemon({ runCatching { p.inputStream.transferTo(OutputStream.nullOutputStream()) } }, "FFmpeg-version-drain")
                    .start()
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    logger.info("Using system ffmpeg: $candidate...")
                    return candidate
                }
                p.destroyForcibly()
            }
        }
        logger.error("FFmpeg not found (no download succeeded, no system binary).")
        return null
    }

    private fun detectPlatform(): Platform? {
        val btbn = { arch: String, ext: String -> "$BTBN_BASE/ffmpeg-n$FFMPEG_VERSION-latest-$arch-gpl-$FFMPEG_VERSION.$ext" }
        return when {
            OsInfo.isWindows -> if (OsInfo.isArm) null else Platform("windows-x64", btbn("win64", "zip"), "ffmpeg.exe", "/bin/ffmpeg.exe")
            OsInfo.isMac -> if (OsInfo.isArm) {
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg9arm.zip", "ffmpeg", "ffmpeg")
            } else {
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/ffmpeg-9.0.2.zip", "ffmpeg", "ffmpeg")
            }
            OsInfo.isArm -> Platform("linux-aarch64", btbn("linuxarm64", "tar.xz"), "ffmpeg", "/bin/ffmpeg")
            else -> Platform("linux-x64", btbn("linux64", "tar.xz"), "ffmpeg", "/bin/ffmpeg")
        }
    }

    private class Platform(val key: String, val url: String, val binaryName: String, val entrySuffix: String)
}
