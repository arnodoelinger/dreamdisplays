package com.dreamdisplays.media.runtime.download

import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name
import kotlin.io.path.outputStream

/** Downloads and unpacks the external tools fetched on first use (`FFmpeg`, its shared libraries, `yt-dlp`). */
object SourceLoader {
    private val logger = LoggerFactory.getLogger(javaClass)
    private const val USER_AGENT = "DreamDisplays-bootstrap"
    private const val MIB = 1_048_576.0

    /** Downloads [url] to [dest]. */
    @Throws(IOException::class)
    fun download(url: String, dest: Path, label: String, readTimeoutMs: Long = 300_000) {
        Files.createDirectories(dest.parent)
        val part = dest.resolveSibling("${dest.name}.part")
        logger.info("Downloading $label from $url...")
        var lastLoggedPct = -10
        try {
            DreamHttpClient.downloadToFile(
                url, part,
                DreamHttpClient.RequestOptions(
                    headers = DreamHttpClient.headersOf("User-Agent" to USER_AGENT),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = readTimeoutMs,
                ),
            ) { downloaded, total ->
                val pct = if (total > 0) (downloaded * 100 / total).toInt() / 10 * 10 else return@downloadToFile
                if (pct < lastLoggedPct + 10) return@downloadToFile
                lastLoggedPct = pct
                logger.info("Downloading $label... $pct% (${"%.1f".format(downloaded / MIB)} / ${"%.1f".format(total / MIB)} MB).")
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.deleteIfExists()
        }
    }

    /** Extracts the entries. */
    @Throws(IOException::class)
    fun extract(archive: Path, destDir: Path, select: (entryName: String) -> String?): Int {
        Files.createDirectories(destDir)
        var count = 0
        fun write(name: String, input: InputStream) {
            val target = select(name) ?: return
            destDir.resolve(target).outputStream().buffered().use { input.transferTo(it) }
            count++
        }
        Files.newInputStream(archive).buffered().use { raw ->
            if (archive.name.endsWith(".zip")) {
                ZipInputStream(raw).use { zip ->
                    generateSequence { zip.nextEntry }
                        .filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") }
                        .forEach { write(it.name, zip) }
                }
            } else {
                TarArchiveInputStream(XZCompressorInputStream(raw)).use { tar ->
                    generateSequence { tar.nextEntry }.filter { it.isFile }.forEach { write(it.name, tar) }
                }
            }
        }
        return count
    }

    /**
     * Downloads the archive at [url], extracts it with [select] into [destDir], deletes the archive, and
     * returns the entry count.
     */
    @Throws(IOException::class)
    fun downloadAndExtract(url: String, destDir: Path, label: String, select: (entryName: String) -> String?): Int {
        val archive = destDir.resolve("_download" + if (url.endsWith(".zip")) ".zip" else ".tar.xz")
        try {
            download(url, archive, label)
            return extract(archive, destDir, select).also { logger.info("$label ready ($it files unpacked).") }
        } finally {
            archive.deleteIfExists()
        }
    }

    /** Marks [file] executable and drops the macOS quarantine flag so it can be spawned. */
    fun makeExecutable(file: Path) {
        Processes.markExecutable(file)
        Processes.removeMacQuarantine(file)
    }
}
