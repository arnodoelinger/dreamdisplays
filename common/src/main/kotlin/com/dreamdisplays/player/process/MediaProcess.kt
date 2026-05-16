package com.dreamdisplays.player.process

import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Builds `FFmpeg` process invocations for the media pipeline and handles their
 * graceful shutdown.
 */
object MediaProcess {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Builds an `FFmpeg` process to read video frames from [url] at the given [offsetNanos], scaled and cropped to [w]x[h].
     * @throws IOException if the process fails to start. The caller is responsible for destroying the process when done.
     */
    @Throws(IOException::class)
    fun buildVideo(ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long): Process {
        val cmd = baseCommand(ffmpeg, url, offsetNanos).apply {
            addAll(
                listOf(
                    "-an",
                    "-vf", "scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h",
                    "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
                )
            )
        }
        return ProcessBuilder(cmd).start()
    }

    /**
     * Builds an `FFmpeg` process to read audio samples from [url] at the given [offsetNanos], resampled to [sampleRate] Hz.
     * @throws IOException if the process fails to start. The caller is responsible for destroying the process when done.
     */
    @Throws(IOException::class)
    fun buildAudio(ffmpeg: String, url: String, offsetNanos: Long, sampleRate: Int): Process {
        val cmd = baseCommand(ffmpeg, url, offsetNanos).apply {
            addAll(listOf("-vn", "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "2", "-"))
        }
        return ProcessBuilder(cmd).start()
    }

    /**
     * Closes the process's output stream and destroys it. Waits up to 1 second for graceful termination, then forcibly destroys if needed.
     * Safe to call multiple times or from any thread. Does nothing if [proc] is null.
     */
    fun gracefulDestroy(proc: Process?) {
        if (proc == null) return
        runCatching { proc.outputStream?.close() }
        proc.destroy()
        try {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
        } catch (_: InterruptedException) {
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    /** Builds the common part of the `FFmpeg` command line for both video and audio processes. */
    private fun baseCommand(ffmpeg: String, url: String, offsetNanos: Long): MutableList<String> =
        mutableListOf<String>().apply {
            add(ffmpeg)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostats"))
            addAll(listOf("-headers", "User-Agent: $USER_AGENT\r\nReferer: https://www.youtube.com/\r\n"))
            addAll(
                listOf(
                    "-reconnect", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "10",
                    "-reconnect_on_network_error", "1",
                    "-reconnect_on_http_error", "4xx,5xx",
                )
            )
            addAll(listOf("-rw_timeout", "15000000"))
            if (offsetNanos > 0) {
                addAll(listOf("-ss", String.format(Locale.US, "%.6f", offsetNanos / 1e9)))
            }
            addAll(listOf("-i", url))
        }
}
