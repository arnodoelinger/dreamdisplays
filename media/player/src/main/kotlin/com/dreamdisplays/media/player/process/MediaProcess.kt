package com.dreamdisplays.media.player.process

import com.dreamdisplays.api.security.policy.MediaHosts
import com.dreamdisplays.media.player.pipeline.VideoFramePipe
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import kotlinx.io.IOException
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

    /** Frame rate assumed when the source reports none, or reports something implausible. */
    const val DEFAULT_OUTPUT_FPS = 30.0

    /** Sanitizes source frame rate to constant pipeline rate. Raw pipes carry no frame-rate metadata. */
    fun outputFps(sourceFps: Double?): Double =
        sourceFps?.takeIf { it.isFinite() && it > 1.0 && it <= 240.0 } ?: DEFAULT_OUTPUT_FPS

    /** True when [stderr] is `FFmpeg` reporting that the input simply has no audio track, rather than any kind of real failure. */
    fun indicatesNoAudioStream(stderr: String): Boolean {
        return stderr.contains("does not contain any stream") && NO_AUDIO_DISQUALIFIERS.none { stderr.contains(it, ignoreCase = true) }
    }

    /** Markers that mean the empty output came from a failed input, not from a silent one. */
    private val NO_AUDIO_DISQUALIFIERS = listOf(
        "Server returned", "Connection refused", "Connection timed out", "Invalid data found",
        "No such file or directory", "Protocol not found", "Immediate exit requested",
        "Error opening input", "Unknown error", "I/O error", "HTTP error",
    )

    /**
     * Request headers for [url]. Platform CDNs get the `Referer` some of them insist on (see
     * [MediaHosts.refererFor]); a host a player pasted gets a plain browser identity and nothing
     * else, so its operator learns no more from the request than any visitor would give them.
     */
    private fun headerArgs(url: String): List<String> {
        val referer = MediaHosts.refererFor(url)?.let { "Referer: $it\r\n" }.orEmpty()
        return listOf("-headers", "User-Agent: $USER_AGENT\r\n$referer")
    }

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

    /** Builds FFmpeg process to read video frames from [url] at offset, scaled and cropped to [w]x[h]. */
    @Throws(IOException::class)
    fun buildVideo(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend, fps: Double,
        alreadyResolved: Boolean = false, seekByDecoding: Boolean = false,
    ): Process =
        ProcessBuilder(
            videoArgs(
                ffmpeg, url, w, h, offsetNanos, hwAccel, VideoTransport.PPM, fps, alreadyResolved, seekByDecoding,
            ),
        ).start()

    /** Builds full FFmpeg argv for video session emitting [transport] on stdout. Used by native pipeline directly. */
    fun videoArgs(
        ffmpeg: String, url: String, w: Int, h: Int, offsetNanos: Long, hwAccel: HwAccelBackend,
        transport: VideoTransport, fps: Double, alreadyResolved: Boolean = false,
        seekByDecoding: Boolean = false,
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
            val fitSw = "min($w/iw\\,$h/ih)"
            "${swPrefix}scale=w=min($w\\,iw*$fitSw):h=min($h\\,ih*$fitSw):flags=bilinear$scaleExtra,$pad"
        }
        return baseCommand(ffmpeg, url, offsetNanos, hwAccel, alreadyResolved, seekByDecoding).apply {
            addAll(listOf("-an", "-vf", vf))
            addAll(listOf("-r", String.format(Locale.US, "%.6f", outputFps(fps))))
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

    /** Builds FFmpeg process that seeks to offset in URL and writes a single letterboxed JPEG frame. */
    @Throws(IOException::class)
    fun buildFrameExtract(
        ffmpeg: String, url: String, offsetNanos: Long, w: Int, h: Int, seekByDecoding: Boolean = false,
    ): Process {
        val fitSw = "min($w/iw\\,$h/ih)"
        val pad = "scale=w=min($w\\,iw*$fitSw):h=min($h\\,ih*$fitSw):flags=lanczos," +
                "pad=w=$w:h=$h:x=(ow-iw)/2:y=(oh-ih)/2:color=black"
        val cmd = baseCommand(
            ffmpeg, url, offsetNanos, HwAccelBackend.NONE,
            alreadyResolved = true, seekByDecoding = seekByDecoding,
        ).apply {
            addAll(
                listOf(
                    "-an", "-frames:v", "1",
                    "-threads", "1",
                    "-vf", pad, "-q:v", "3",
                    "-f", "image2pipe", "-vcodec", "mjpeg", "-",
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
    fun buildAudio(
        ffmpeg: String, url: String, offsetNanos: Long, sampleRate: Int, seekByDecoding: Boolean = false,
    ): Process {
        val cmd = baseCommand(
            ffmpeg, url, offsetNanos, HwAccelBackend.NONE, seekByDecoding = seekByDecoding,
        ).apply {
            addAll(listOf("-vn", "-f", "s16le", "-ar", sampleRate.toString(), "-ac", "2", "-"))
        }
        return ProcessBuilder(cmd).start()
    }

    /** Builds `FFmpeg` process that decodes audio from MPEG-TS piped to stdin. */
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
        runCatching {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
        }.onFailure { e ->
            if (e !is InterruptedException) throw e
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    /** Builds common `FFmpeg` command line for video and audio. [alreadyResolved] skips SSRF guard. */
    @Throws(IOException::class)
    private fun baseCommand(
        ffmpeg: String,
        url: String,
        offsetNanos: Long,
        hwAccel: HwAccelBackend,
        alreadyResolved: Boolean = false,
        seekByDecoding: Boolean = false,
    ): MutableList<String> {
        val safeUrl = if (alreadyResolved) url else MediaHostGuard.resolveSafeUrl(url)
        // This container's demuxer cannot be trusted with a jump, so the best available seek is a
        // playlist that already starts at the target ([HlsSeekPlaylist]).
        val trimmed =
            if (seekByDecoding && offsetNanos > 0) HlsSeekPlaylist.trim(safeUrl, offsetNanos) else null
        return inputCommand(ffmpeg, safeUrl, offsetNanos, hwAccel, seekByDecoding, trimmed)
    }

    /** Input half of `FFmpeg` invocation with playlist trim already decided. */
    internal fun inputCommand(
        ffmpeg: String,
        safeUrl: String,
        offsetNanos: Long,
        hwAccel: HwAccelBackend,
        seekByDecoding: Boolean,
        trimmed: HlsSeekPlaylist.Trimmed?,
    ): MutableList<String> {
        return mutableListOf<String>().apply {
            add(ffmpeg)
            addAll(listOf("-hide_banner", "-loglevel", "error", "-nostats"))
            // TODO: httpproxy is sus
            addAll(listOf("-protocol_whitelist", "https,tls,tcp,crypto,data,http,httpproxy"))
            // https://github.com/arnodoelinger/dreamdisplays/issues/218
            // TODO: i should rewrite some parts of code to fix tls issue properly but i'm too lazy
            addAll(listOf("-tls_verify", "0")) // https://github.com/arnodoelinger/dreamdisplays/issues/209
            if (hwAccel.ffmpegName != null) {
                addAll(listOf("-hwaccel", hwAccel.ffmpegName))
            }
            hwAccel.hwOutputFormat?.let { addAll(listOf("-hwaccel_output_format", it)) }
            if (trimmed == null) {
                addHttpOptions(safeUrl)
            } else {
                // Every option below belongs to the http protocol, and the input here is a data:
                // URI, so none of them has a context to be applied to: FFmpeg reports the first one
                // as "Option headers not found" and refuses to open the input at all. The HLS
                // demuxer's own retry counters are protocol-independent and cover the same ground
                // for the segment fetches, which are the requests that actually matter here.
                addAll(listOf("-seg_max_retry", "3", "-max_reload", "3"))
            }
            addAll(listOf("-rw_timeout", "15000000"))
            // The sources here are plain MP4 / WebM / M4A over HTTPS with stream info in their headers;
            // the default 5 MB / 5 s probe window only postpones the first frame, so keep it tight.
            // Deliberately no -fflags nobuffer: it drops the leading GOP on open and on every
            // in-place seek (see native/lav/src/session.rs, which dropped the same flag for the
            // same reason).
            addAll(listOf("-probesize", "1M", "-analyzeduration", "1000000"))
            when {
                trimmed != null -> {
                    // Forcing the demuxer is required: FFmpeg will not probe a playlist out of a
                    // data: URI ("Not detecting m3u8/hls with non-standard extension").
                    addAll(listOf("-f", "hls", "-i", trimmed.url))
                    if (trimmed.residualNanos > 0) addAll(seekArgs(trimmed.residualNanos))
                }
                // -ss after -i makes FFmpeg decode from the start and discard: always correct,
                // and slower the deeper the seek. Only reached when the playlist could not be read.
                seekByDecoding -> {
                    addAll(listOf("-i", safeUrl))
                    if (offsetNanos > 0) addAll(seekArgs(offsetNanos))
                }
                // -ss before -i asks the demuxer to jump, which is what makes a seek instant
                else -> {
                    if (offsetNanos > 0) addAll(seekArgs(offsetNanos))
                    addAll(listOf("-i", safeUrl))
                }
            }
        }
    }

    /** Connection options for an `http(s)` input; see [baseCommand] for why they are conditional. */
    private fun MutableList<String>.addHttpOptions(url: String) {
        addAll(headerArgs(url))
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
    }

    /** `-ss` with [offsetNanos] rendered as seconds. */
    private fun seekArgs(offsetNanos: Long): List<String> =
        listOf("-ss", String.format(Locale.US, "%.6f", offsetNanos / 1e9))
}
