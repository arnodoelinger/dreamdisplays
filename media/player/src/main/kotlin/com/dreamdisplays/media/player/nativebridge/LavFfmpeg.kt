package com.dreamdisplays.media.player.nativebridge

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.io.IOException
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI

/** Downloads and unpacks `FFmpeg` shared libraries from BtbN for the in-process libav backend. */
object LavFfmpeg {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest"
    private const val FFMPEG_BRANCH_SUFFIX = "9.0"

    private class Source(val arch: String, val extension: String, val libDir: String)

    /** Ensures [dir] contains `FFmpeg` libraries, downloading and unpacking them on first run. */
    fun ensure(dir: File): Boolean {
        if (hasFfmpeg(dir)) return true
        val source = when {
            OsInfo.isMac -> return false
            OsInfo.isWindows -> Source(if (OsInfo.isArm64) "winarm64" else "win64", "zip", "bin")
            else -> Source(if (OsInfo.isArm64) "linuxarm64" else "linux64", "tar.xz", "lib")
        }
        return runCatching {
            logger.info("FFmpeg libraries not found — downloading from BtbN builds...")
            SourceLoader.downloadAndExtract(resolveLatestAssetUrl(source), dir.toPath(), "FFmpeg libraries") { name ->
                File(name).name.takeIf { wantedEntry(name, source.libDir) }
            }
            hasFfmpeg(dir)
        }.getOrElse { e ->
            logger.warn("Could not provision FFmpeg libraries (${e.javaClass.simpleName}: ${e.message}).")
            false
        }
    }

    private fun hasFfmpeg(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.lowercase().let { n -> "avcodec" in n && isSharedLibrary(n) } } == true

    private fun isSharedLibrary(name: String): Boolean = name.endsWith(".dll") || name.endsWith(".dylib") || ".so" in name

    private fun wantedEntry(entryName: String, libDir: String): Boolean {
        val parts = entryName.split('/')
        val leaf = parts.last()
        return parts.size >= 2 && parts[parts.size - 2] == libDir && isSharedLibrary(leaf.lowercase()) ||
                leaf.equals("LICENSE.txt", ignoreCase = true) && parts.size <= 2
    }

    @Throws(IOException::class)
    private fun resolveLatestAssetUrl(source: Source): String {
        val pattern = Regex(
            "^ffmpeg-n9\\..*-${Regex.escape(source.arch)}-lgpl-shared-${Regex.escape(FFMPEG_BRANCH_SUFFIX)}\\.${Regex.escape(source.extension)}$"
        )
        val json = DreamHttpClient.readText(
            LATEST_RELEASE_API,
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf(
                    "User-Agent" to "DreamDisplays-bootstrap",
                    "Accept" to "application/vnd.github+json",
                ),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000,
            ),
        )
        return Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")
            .findAll(json)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { pattern.matches(File(URI.create(it).path).name) }
            ?: throw IOException("No BtbN FFmpeg asset matched ${pattern.pattern}.")
    }
}
