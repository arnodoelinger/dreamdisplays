package com.dreamdisplays.media.player.process

import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks that the system `libva` can serve the bundled BtbN `FFmpeg`,
 * whose lazy `libva` stubs abort the whole process on the first missing symbol.
 */
internal object LibvaCompat {
    private val logger = LoggerFactory.getLogger(javaClass)

    private const val LIBRARY = "libva.so.2"
    private const val REQUIRED_SYMBOL = "vaMapBuffer2"
    private const val MAX_LIBRARY_BYTES = 16L shl 20
    private val LIBRARY_DIRS = [
        "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib/x86_64-linux-gnu", "/lib/aarch64-linux-gnu",
        "/usr/lib64", "/usr/lib", "/lib64", "/lib",
    ]

    /** `true` when VAAPI decoding through the bundled `FFmpeg` won't abort on this machine's `libva`. */
    val vaapiUsable: Boolean by lazy {
        if (!OsInfo.isLinux) return@lazy false
        val library = locate() ?: run {
            logger.info("$LIBRARY not found; VAAPI hardware decoding disabled.")
            return@lazy false
        }
        val usable = runCatching { exports(library, REQUIRED_SYMBOL) }.getOrDefault(false)
        if (!usable) {
            logger.warn(
                "${library.absolutePath} lacks $REQUIRED_SYMBOL (libva older than 2.21); " +
                    "VAAPI hardware decoding disabled, videos decode in software."
            )
        }
        usable
    }

    /**
     * `true` when [library]'s dynamic string table names [symbol];
     * a missing export never appears there.
     */
    internal fun exports(library: File, symbol: String): Boolean {
        if (library.length() !in 1..MAX_LIBRARY_BYTES) return false
        val needle = (symbol + '\u0000').toByteArray(Charsets.US_ASCII)
        val bytes = library.readBytes()
        outer@ for (i in 0..bytes.size - needle.size) {
            if (i > 0 && bytes[i - 1] != 0.toByte()) continue
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun locate(): File? {
        val fromPath = System.getenv("LD_LIBRARY_PATH").orEmpty().split(':')
            .filter { it.isNotBlank() }
            .map { File(it, LIBRARY) }
            .firstOrNull { it.isFile }
        return (fromPath ?: fromLdCache() ?: LIBRARY_DIRS.map { File(it, LIBRARY) }.firstOrNull { it.isFile })
            ?.let { runCatching { it.canonicalFile }.getOrDefault(it) }
    }

    private fun fromLdCache(): File? {
        val arch = if (OsInfo.isArm64) "AArch64" else "x86-64"
        for (ldconfig in ["/sbin/ldconfig", "/usr/sbin/ldconfig", "ldconfig"]) {
            val output = runCatching {
                val process = ProcessBuilder(ldconfig, "-p").redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                text
            }.getOrNull() ?: continue
            return output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("$LIBRARY ") && arch in it }
                .mapNotNull { it.substringAfter("=>", "").trim().takeIf(String::isNotEmpty) }
                .map(::File)
                .firstOrNull { it.isFile }
        }
        return null
    }
}
