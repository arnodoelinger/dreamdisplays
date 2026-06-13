package com.dreamdisplays.player.nativebridge

import com.dreamdisplays.utils.OsInfo
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Provides the `FFmpeg` shared libraries the in-process libav backend
 * ([NativeMedia.initLav]) links against. They are no longer bundled in the jar;
 * instead a prebuilt shared build is downloaded on demand and unpacked next to
 * `dreamdisplays_lav`, where [NativeMedia.preloadLavDependencies] picks it up.
 *
 * The download must match the build the library was linked against (matching
 * SONAMEs / DLL names), so the build tag here is kept in lockstep with the one
 * used by CI when compiling the native libraries. macOS has no prebuilt shared
 * build available, so it keeps relying on a system (Homebrew) FFmpeg.
 */
object LavFfmpeg {
    private val logger = LoggerFactory.getLogger("DreamDisplays/LavFFmpeg")

    /** BtbN release tag and version suffix; keep in sync with `.github/workflows/build.yml`. */
    private const val BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"
    private const val BUILD = "n8.1-latest"
    private const val SUFFIX = "8.1"

    private data class Source(
        /** Archive download URL. */
        val url: String,
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
                logger.info("Downloading FFmpeg libraries from ${source.url}...")
                downloadWithRedirects(source.url, archive)
                val count =
                    if (source.isTarXz) extractTarXzLibs(archive, source.libDir, dir)
                    else extractZipLibs(archive, source.libDir, dir)
                logger.info("Unpacked $count FFmpeg files into $dir.")
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
        dir.listFiles()?.any { it.isFile && it.name.lowercase().let { n -> "avcodec" in n && isSharedLibrary(n) } } == true

    private fun isSharedLibrary(name: String): Boolean =
        name.endsWith(".dll") || name.endsWith(".dylib") || ".so" in name

    /** Resolves the prebuilt shared build for this platform, or null when none is available. */
    private fun source(): Source? = when {
        OsInfo.isMac -> null
        OsInfo.isWindows -> {
            val arch = if (OsInfo.isArm64) "winarm64" else "win64"
            Source("$BASE/ffmpeg-$BUILD-$arch-lgpl-shared-$SUFFIX.zip", isTarXz = false, libDir = "bin")
        }
        else -> {
            val arch = if (OsInfo.isArm64) "linuxarm64" else "linux64"
            Source("$BASE/ffmpeg-$BUILD-$arch-lgpl-shared-$SUFFIX.tar.xz", isTarXz = true, libDir = "lib")
        }
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

    /** Downloads [url] to [dest], following up to 10 redirect hops (GitHub releases use several). */
    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        var current = url
        for (hop in 0 until 10) {
            val conn = URI.create(current).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "DreamDisplays-lav-ffmpeg")
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            val status = conn.responseCode
            if (status in 300..399) {
                val loc = conn.getHeaderField("Location") ?: run {
                    conn.disconnect(); throw IOException("Redirect without Location at $current.")
                }
                conn.disconnect()
                current = loc
                continue
            }
            if (status != 200) {
                conn.disconnect(); throw IOException("HTTP $status for $current.")
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
}
