package com.dreamdisplays.media.source.youtube.binary

import com.dreamdisplays.media.runtime.download.SourceLoader
import com.dreamdisplays.util.DreamCoroutines
import com.dreamdisplays.util.OsInfo
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

object DenoBinary {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val CACHE_ROOT = "./dreamdisplays/deno"
    private const val DENO_VERSION = "2.9.8"
    private const val RELEASE_BASE = "https://github.com/denoland/deno/releases/download/v$DENO_VERSION"

    private val downloading = atomic(false)

    @Volatile
    private var failed = false

    fun pathOrStartDownload(): String? {
        val p = platform() ?: return null
        val binary = binaryFile(p)
        if (isUsable(binary)) return binary.absolutePath
        if (!failed && downloading.compareAndSet(expect = false, update = true)) {
            DreamCoroutines.clientIo.launch {
                try {
                    download(p, binary)
                } catch (e: Exception) {
                    failed = true
                    logger.warn("Deno download failed; restricted YouTube videos will not play: ${e.message}.")
                } finally {
                    downloading.value = false
                }
            }
        }
        return null
    }

    private fun download(p: Platform, binary: File) {
        val dir = binary.parentFile
        SourceLoader.downloadAndExtract("$RELEASE_BASE/deno-${p.target}.zip", dir.toPath(), "Deno") { name ->
            binary.name.takeIf { File(name).name == binary.name }
        }
        if (!binary.isFile || binary.length() == 0L) throw IOException("Extracted binary is missing or empty.")
        SourceLoader.makeExecutable(binary.toPath())
        deleteStaleBuilds(dir.name)
        logger.info("Deno ready for yt-dlp: {}.", binary.absolutePath)
    }

    private fun deleteStaleBuilds(current: String) {
        File(CACHE_ROOT).listFiles()
            ?.filter { it.isDirectory && it.name != current }
            ?.forEach { if (it.deleteRecursively()) logger.info("Removed stale Deno build: ${it.name}.") }
    }

    private fun binaryFile(p: Platform): File =
        File("$CACHE_ROOT/${p.target}-$DENO_VERSION", if (OsInfo.isWindows) "deno.exe" else "deno")

    private fun isUsable(f: File): Boolean = f.isFile && f.length() > 0 && f.canExecute()

    private fun platform(): Platform? {
        if (File("/system/build.prop").exists()) return null
        val arch = when {
            OsInfo.isArm64 -> "aarch64"
            OsInfo.isArm -> return null
            else -> "x86_64"
        }
        return when {
            OsInfo.isWindows -> Platform("$arch-pc-windows-msvc")
            OsInfo.isMac -> Platform("$arch-apple-darwin")
            OsInfo.isLinux -> Platform("$arch-unknown-linux-gnu")
            else -> null
        }
    }

    private class Platform(val target: String)
}
