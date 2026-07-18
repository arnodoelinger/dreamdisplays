package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.process.FFmpegCapabilities.PROBED_FILTERS
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * One-time probe of optional `FFmpeg` features (hardware scale filters etc.) for a given binary.
 *
 * Building the filter graph around a filter the binary doesn't have fails the whole session at
 * runtime, so callers check here first; the result is cached per binary path for the process
 * lifetime. Probing spawns `ffmpeg -filters` once (~30 ms) on first use.
 */
internal object FFmpegCapabilities {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FFmpegCapabilities")

    private val filterCache = ConcurrentHashMap<String, Set<String>>()

    /** Filters we may want to use; only these are looked for in the probe output. */
    private val PROBED_FILTERS = setOf("scale_vt", "scale_cuda", "scale_vaapi")

    /** True when [ffmpeg] supports filter [name] (e.g. `scale_vt`). */
    fun hasFilter(ffmpeg: String, name: String): Boolean = probedFilters(ffmpeg).contains(name)

    /** Returns the probed subset of [PROBED_FILTERS] available in [ffmpeg], caching per path. */
    private fun probedFilters(ffmpeg: String): Set<String> =
        filterCache.computeIfAbsent(ffmpeg) { bin ->
            try {
                val proc = ProcessBuilder(bin, "-hide_banner", "-filters")
                    .redirectErrorStream(true)
                    .start()
                runCatching { proc.outputStream.close() }
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    logger.warn("FFmpeg -filters probe timed out; assuming no optional filters.")
                    return@computeIfAbsent emptySet()
                }
                // Each line lists the filter name as a whitespace-delimited column.
                PROBED_FILTERS.filterTo(HashSet()) { f -> output.contains(" $f ") }
                    .also { logger.info("FFmpeg optional filters available: $it.") }
            } catch (e: Exception) {
                logger.warn("FFmpeg -filters probe failed (${e.message}); assuming no optional filters.")
                emptySet()
            }
        }
}
