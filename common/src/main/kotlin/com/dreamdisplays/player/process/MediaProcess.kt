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
     *
     * When [hwAccel] is anything other than [HwAccelBackend.NONE], the decoder is asked to run on
     * the GPU's video block. The pipeline still pulls RGB out via a pipe, so `FFmpeg` inserts an
     * implicit `hwdownload`; the win is that the heavy decoding step no longer burns a CPU core.
     *
     * @throws IOException if the process fails to start. the caller is responsible for destroying the process when done.
     */
    @Throws(IOException::class)
    fun buildVideo(ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend): Process {
        val swPrefix = if (hwAccel.hwOutputFormat != null) "hwdownload,format=nv12," else ""
        val vf = "${swPrefix}scale=w=$w:h=$h:force_original_aspect_ratio=decrease:flags=bilinear,pad=$w:$h:-1:-1"
        val cmd = baseCommand(ffmpeg, url, offsetNanos, hwAccel).apply {
            addAll(
                listOf(
                    "-an",
                    "-vf", vf,
                    "-f", "image2pipe", "-c:v", "ppm", "-",
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
        val cmd = baseCommand(ffmpeg, url, offsetNanos, HwAccelBackend.NONE).apply {
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
    private fun baseCommand(ffmpeg: String, url: String, offsetNanos: Long, hwAccel: HwAccelBackend): MutableList<String> =
        mutableListOf<String>().apply {
            add(ffmpeg)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostats"))
            if (hwAccel.ffmpegName != null) {
                addAll(listOf("-hwaccel", hwAccel.ffmpegName))
            }
            hwAccel.hwOutputFormat?.let { addAll(listOf("-hwaccel_output_format", it)) }
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
