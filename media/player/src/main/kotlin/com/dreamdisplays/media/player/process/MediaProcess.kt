package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.pipeline.VideoFramePipe
import com.dreamdisplays.media.runtime.MediaHostGuard
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

    /** Kill switch for GPU-side scaling (`-Ddreamdisplays.hwscale=false` falls back to software scale). */
    private val HW_SCALE_ENABLED = System.getProperty("dreamdisplays.hwscale", "true").toBoolean()

    /** Wire format the video `FFmpeg` process writes to its stdout pipe. */
    enum class VideoTransport {
        /** PPM frames (header + RGB24), parsed by the JVM [VideoFramePipe]. */
        PPM,

        /** Headerless RGB24 rawvideo, consumed by the native pipeline. */
        RAW_RGB24,

        /**
         * Headerless NV12 rawvideo, consumed by the native pipeline. Halves pipe traffic vs.
         * RGB24; the stream is pinned to BT.709 so the native converter can use a fixed matrix.
         */
        RAW_NV12,
    }

    /**
     * Builds an `FFmpeg` process to read video frames from [url] at the given [offsetNanos], scaled and cropped to [w] x [h].
     *
     * When [hwAccel] is anything other than [HwAccelBackend.NONE], the decoder is asked to run on
     * the GPU's video block. The pipeline still pulls RGB out via a pipe, so `FFmpeg` inserts an
     * implicit `hwdownload`; the win is that the heavy decoding step no longer burns a CPU core.
     *
     * @throws IOException if the process fails to start. the caller is responsible for destroying the process when done.
     */
    @Throws(IOException::class)
    fun buildVideo(ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend): Process =
        ProcessBuilder(videoArgs(ffmpeg, url, w, h, offsetNanos, hwAccel, VideoTransport.PPM)).start()

    /**
     * Builds the full `FFmpeg` argv for a video session emitting [transport] on stdout.
     * Used directly by the native pipeline (which spawns the process itself) and by [buildVideo].
     */
    fun videoArgs(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend,
        transport: VideoTransport,
    ): List<String> {
        val outFormat = if (transport == VideoTransport.RAW_NV12) "nv12" else "rgb24"
        val pad = "pad=w=$w:h=$h:x=(ow-iw)/2:y=(oh-ih)/2:color=black,setsar=1,format=$outFormat"
        val vf = if (useHwScale(ffmpeg, hwAccel)) {
            // Scale on the GPU's video block before hwdownload so the CPU never touches
            // full-resolution frames.
            val fit = "min($w/iw\\,$h/ih)"
            val matrix = if (transport == VideoTransport.RAW_NV12) ":color_matrix=bt709" else ""
            "scale_vt=w=trunc($fit*iw/2)*2:h=trunc($fit*ih/2)*2$matrix,hwdownload,format=nv12,$pad"
        } else {
            val swPrefix = if (hwAccel.hwOutputFormat != null) "hwdownload,format=nv12," else ""
            val scaleExtra = if (transport == VideoTransport.RAW_NV12) ":out_color_matrix=bt709" else ""
            "${swPrefix}scale=w=$w:h=$h:force_original_aspect_ratio=decrease:flags=bilinear$scaleExtra,$pad"
        }
        return baseCommand(ffmpeg, url, offsetNanos, hwAccel).apply {
            addAll(listOf("-an", "-vf", vf))
            when (transport) {
                VideoTransport.PPM -> addAll(listOf("-f", "image2pipe", "-c:v", "ppm", "-"))
                VideoTransport.RAW_RGB24, VideoTransport.RAW_NV12 -> addAll(listOf("-f", "rawvideo", "-"))
            }
        }
    }

    /** True when the GPU-side scale chain should be used instead of software scaling. */
    private fun useHwScale(ffmpeg: String, hwAccel: HwAccelBackend): Boolean =
        HW_SCALE_ENABLED
                && hwAccel == HwAccelBackend.VIDEOTOOLBOX
                && FFmpegCapabilities.hasFilter(ffmpeg, "scale_vt")

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
     * Builds an `FFmpeg` process that decodes audio from MPEG-TS piped into its stdin (see
     * `HlsAudioFeeder`), resampled to [sampleRate] Hz. No network flags: the process never opens a
     * connection itself, which sidesteps the bundled binary's flaky TLS on live HLS hosts.
     *
     * @throws IOException if the process fails to start. The caller is responsible for destroying it.
     */
    @Throws(IOException::class)
    fun buildAudioPiped(ffmpeg: String, sampleRate: Int): Process {
        val cmd = listOf(
            ffmpeg,
            "-hide_banner", "-loglevel", "error", "-nostats",
            "-probesize", "1M", "-analyzeduration", "1000000",
            "-f", "mpegts", "-i", "pipe:0",
            "-vn", "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "2", "-",
        )
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
    @Throws(IOException::class)
    private fun baseCommand(
        ffmpeg: String,
        url: String,
        offsetNanos: Long,
        hwAccel: HwAccelBackend
    ): MutableList<String> {
        val safeUrl = MediaHostGuard.resolveSafeUrl(url)
        return mutableListOf<String>().apply {
            add(ffmpeg)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostats"))
            addAll(listOf("-protocol_whitelist", "https,tls,tcp,crypto,data,http"))
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
                    "-reconnect_on_http_error", "5xx",
                    // Pull googlevideo over one connection with range requests so it doesn't cut at ~10s
                    "-multiple_requests", "1",
                )
            )
            addAll(listOf("-rw_timeout", "15000000"))
            // The sources here are plain MP4 / WebM / M4A over HTTPS with stream info in their headers;
            // the default 5 MB / 5 s probe window only postpones the first frame, so keep it tight and
            // skip input-side buffering (the playback prebuffer smooths jitter downstream).
            addAll(listOf("-probesize", "1M", "-analyzeduration", "1000000", "-fflags", "nobuffer"))
            if (offsetNanos > 0) {
                addAll(listOf("-ss", String.format(Locale.US, "%.6f", offsetNanos / 1e9)))
            }
            addAll(listOf("-i", safeUrl))
        }
    }
}
