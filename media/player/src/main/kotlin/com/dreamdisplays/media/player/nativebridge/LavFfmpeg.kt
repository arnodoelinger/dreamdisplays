package com.dreamdisplays.media.player.nativebridge

import com.dreamdisplays.media.runtime.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import kotlinx.io.IOException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Provides the `FFmpeg` shared libraries the in-process libav backend
 * ([NativeMedia.initLav]) links against. When the extracted native cache does not
 * already contain them, a prebuilt shared build is downloaded on demand and
 * unpacked next to `dreamdisplays_lav`, where [NativeMedia.preloadLavDependencies]
 * picks it up.
 *
 * The download must match the `FFmpeg` major branch the library was linked against
 * (matching SONAMEs / DLL names). BtbN's autobuild asset names include a moving
 * git revision, so URLs are resolved from the latest GitHub release at runtime.
 * macOS has no prebuilt shared build available, so it keeps relying on a system
 * (Homebrew) `FFmpeg`.
 */
object LavFfmpeg {
    private val logger = LoggerFactory.getLogger("DreamDisplays/LavFFmpeg")

    /** BtbN latest release API; keep the branch suffix in sync with `.github/workflows/_build.yml`. */
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest"
    private const val FFMPEG_BRANCH_SUFFIX = "8.1"

    private data class Source(
        /** BtbN asset name matcher for the latest release. */
        val assetNameRegex: Regex,
        /** True for `.tar.xz` (Linux), false for `.zip` (Windows). */
        val isTarXz: Boolean,
        /** In-archive directory holding the shared libraries (`bin` on Windows, `lib` on Linux). */
        val libDir: String,
    )

    /**
     * Ensures [dir] contains the FFmpeg shared libraries, downloading and
     * unpacking them once if needed. Returns true when they are present
     * afterwards. Best-effort: any failure (no network, unsupported platform)
     * just returns false and leaves the in-process backend unavailable.
     */
    fun ensure(dir: File): Boolean {
        if (hasFfmpeg(dir)) return true
        val source = source() ?: return false
        return try {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir.")
            val archive = File(dir, "_ffmpeg" + if (source.isTarXz) ".tar.xz" else ".zip")
            try {
                val url = resolveLatestAssetUrl(source)
                logger.info("FFmpeg not found — downloading from BtbN builds...")
                downloadWithProgress(url, archive)
                logger.info("Unpacking FFmpeg libraries...")
                val count =
                    if (source.isTarXz) extractTarXzLibs(archive, source.libDir, dir)
                    else extractZipLibs(archive, source.libDir, dir)
                logger.info("FFmpeg ready ($count files unpacked).")
            } finally {
                if (archive.exists() && !archive.delete()) archive.deleteOnExit()
            }
            hasFfmpeg(dir)
        } catch (e: Exception) {
            logger.warn("Could not provision FFmpeg libraries (${e.javaClass.simpleName}: ${e.message}).")
            false
        }
    }

    /** True once at least the core decode library is present in [dir]. */
    private fun hasFfmpeg(dir: File): Boolean =
        dir.listFiles()
            ?.any { it.isFile && it.name.lowercase().let { n -> "avcodec" in n && isSharedLibrary(n) } } == true

    private fun isSharedLibrary(name: String): Boolean =
        name.endsWith(".dll") || name.endsWith(".dylib") || ".so" in name

    /** Resolves the prebuilt shared build for this platform, or null when none is available. */
    private fun source(): Source? = when {
        OsInfo.isMac -> null
        OsInfo.isWindows -> {
            val arch = if (OsInfo.isArm64) "winarm64" else "win64"
            Source(assetRegex(arch, "zip"), isTarXz = false, libDir = "bin")
        }

        else -> {
            val arch = if (OsInfo.isArm64) "linuxarm64" else "linux64"
            Source(assetRegex(arch, "tar.xz"), isTarXz = true, libDir = "lib")
        }
    }

    private fun assetRegex(arch: String, extension: String): Regex =
        Regex(
            """^ffmpeg-n8\..*-${Regex.escape(arch)}-lgpl-shared-${Regex.escape(FFMPEG_BRANCH_SUFFIX)}\.${
                Regex.escape(
                    extension
                )
            }$"""
        )

    @Throws(IOException::class)
    private fun resolveLatestAssetUrl(source: Source): String {
        val json = readUrl(LATEST_RELEASE_API)
        val urls = Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")
            .findAll(json)
            .map { it.groupValues[1].replace("\\/", "/") }
        return urls.firstOrNull { source.assetNameRegex.matches(File(URI.create(it).path).name) }
            ?: throw IOException("No BtbN FFmpeg asset matched ${source.assetNameRegex.pattern}.")
    }

    /** Extracts every shared library (and the LICENSE) under `<root>/[libDir]/` from a zip into [dir]. */
    @Throws(IOException::class)
    private fun extractZipLibs(archive: File, libDir: String, dir: File): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && wantedEntry(name, libDir)) {
                    writeEntry(zis, File(dir, File(name).name))
                    count++
                }
                e = zis.nextEntry
            }
        }
        return count
    }

    /** Extracts every shared library (and the LICENSE) under `<root>/[libDir]/` from a tar.xz into [dir]. */
    @Throws(IOException::class)
    private fun extractTarXzLibs(archive: File, libDir: String, dir: File): Int {
        var count = 0
        BufferedInputStream(FileInputStream(archive)).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        // Skip symlinks (BtbN ships e.g. libavcodec.so -> .so.62);
                        // the real SONAME file is what the library needs.
                        if (e.isFile && wantedEntry(e.name, libDir)) {
                            writeEntry(tar, File(dir, File(e.name).name))
                            count++
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        return count
    }

    /** Matches `<root>/<libDir>/<sharedLibrary>` entries plus a top-level LICENSE file. */
    private fun wantedEntry(entryName: String, libDir: String): Boolean {
        val parts = entryName.split('/')
        val leaf = parts.last()
        if (parts.size >= 2 && parts[parts.size - 2] == libDir && isSharedLibrary(leaf.lowercase())) return true
        return leaf.equals("LICENSE.txt", ignoreCase = true) && parts.size <= 2
    }

    @Throws(IOException::class)
    private fun writeEntry(input: java.io.InputStream, dest: File) {
        BufferedOutputStream(FileOutputStream(dest)).use { out -> input.transferTo(out) }
    }

    /** Downloads [url] to [dest] with periodic progress log lines (every 10 %). */
    @Throws(IOException::class)
    private fun downloadWithProgress(url: String, dest: File) {
        var announced = false
        var lastLoggedPct = -1
        DreamHttpClient.downloadToFile(
            url,
            dest.toPath(),
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-lav-ffmpeg"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 300_000,
            ),
        ) { downloaded, total ->
            if (!announced) {
                val totalMb = if (total > 0) "%.1f MB".format(total / 1_048_576.0) else "unknown size"
                logger.info("Downloading FFmpeg ($totalMb)...")
                announced = true
            }
            if (total > 0) {
                val pct = (downloaded * 100 / total).toInt() / 10 * 10
                if (pct > lastLoggedPct) {
                    lastLoggedPct = pct
                    val dlMb = "%.1f".format(downloaded / 1_048_576.0)
                    val totalMb = "%.1f MB".format(total / 1_048_576.0)
                    logger.info("Downloading FFmpeg... $pct% ($dlMb / $totalMb).")
                }
            }
        }
    }

    /** Reads [url], following up to 10 redirect hops. */
    @Throws(IOException::class)
    private fun readUrl(url: String): String {
        return DreamHttpClient.readText(
            url,
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf(
                    "User-Agent" to "DreamDisplays-lav-ffmpeg",
                    "Accept" to "application/vnd.github+json",
                ),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000,
            ),
        )
    }
}
