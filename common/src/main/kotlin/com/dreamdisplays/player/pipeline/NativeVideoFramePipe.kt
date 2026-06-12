package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.nativebridge.NativeMedia
import com.dreamdisplays.player.process.HwAccelBackend
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.render.DisplayYuvRenderTypes
import com.dreamdisplays.render.UploadPixelFormat
import com.mojang.blaze3d.textures.GpuTexture
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native (Rust) variant of [VideoFramePipe]: the FFmpeg process and its pipe live inside
 * `dreamdisplays_native`, which reads NV12/RGB24 frames in large blocks and writes
 * brightness-adjusted RGB24 or RGBA32 directly into the spare direct buffer in one fused pass.
 *
 * The JVM side keeps only what it is good at: A/V pacing against the audio clock,
 * the ready-buffer swap, and the GPU upload — all shared with the JVM pipe via [FrameSurface].
 */
internal class NativeVideoFramePipe(private val debugLabel: String) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplays/NativeVideoFramePipe")

    companion object {
        /** Default frame rate when the source doesn't report one or reports an invalid one. */
        private const val DEFAULT_FPS = 30.0

        /** How long to wait for FFmpeg's exit code after EOF, mirroring the JVM pipe. */
        private const val EXIT_WAIT_MILLIS = 500

        private const val LAV_HW_AUTO = 1
        private const val LAV_PTS_ORIGIN_TOLERANCE_NS = 10_000_000_000L
        private const val LAV_PREROLL_MARGIN_NS = 50_000_000L
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    override val lastFrameReceivedNanos = AtomicLong(0)

    /** Set by the popout window to receive raw frames. Called on the reader thread. */
    @Volatile override var popoutFrameSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? = null

    @Volatile var expectedW = 0
        private set
    @Volatile var expectedH = 0
        private set

    /** True when frames stay as raw I420 planes and the YUV -> RGB conversion happens on the GPU. */
    private val planarOutput = DisplayYuvRenderTypes.active

    private val outputFormat: UploadPixelFormat =
        if (NativeMedia.rgbaFramesEnabled) UploadPixelFormat.RGBA32 else UploadPixelFormat.RGB24
    private val surface = FrameSurface(debugLabel, outputFormat)

    /** Scratch RGBA buffer for the popout window in planar mode (it still wants RGBA frames). */
    private var popoutRgba: ByteBuffer? = null

    @Volatile private var handle = 0L

    /** Handle of the in-process libav session, when [startInProcess] is used instead of FFmpeg. */
    @Volatile private var lavHandle = 0L

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int) =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

    override fun updateFramePlanar(y: GpuTexture, u: GpuTexture, v: GpuTexture, actualW: Int, actualH: Int) =
        surface.updateFramePlanar(y, u, v, actualW, actualH, expectedW, expectedH)

    override fun clear() = surface.clear()

    override fun cleanup() = surface.cleanup()

    /**
     * Opens a native FFmpeg session for [args] and starts the reader thread.
     * Returns the running thread, or null when the native session could not be spawned
     * (e.g., the FFmpeg binary is missing).
     *
     * @param args            full FFmpeg argv including the binary path (see `MediaProcess.videoArgs`)
     * @param nv12            true when [args] makes FFmpeg emit NV12 instead of RGB24
     * @param seekOffsetNanos initial playback position (must match the FFmpeg `-ss` offset)
     * @param sourceFps       frame rate reported by yt-dlp for the chosen stream
     * @param getAudioClock   returns current audio position in nanos, or -1 if unavailable
     * @param onFirstFrame    called once when the first frame arrives (starts the wall clock)
     * @param getBrightness   returns current brightness multiplier (read per frame)
     * @param onEos           called when the stream ends with stderr output and EOS flag
     */
    fun start(args: List<String>, w: Int, h: Int, nv12: Boolean, seekOffsetNanos: Long, sourceFps: Double,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ): Thread? {
        release()
        clear()
        expectedW = w
        expectedH = h
        lastFrameReceivedNanos.set(System.nanoTime())

        val hnd = NativeMedia.videoOpen(args, w, h, nv12)
        if (hnd == 0L) {
            logger.error("$debugLabel Native FFmpeg session failed to start.")
            return null
        }
        handle = hnd
        val frameNs = (1_000_000_000.0 / (sourceFps.takeIf { it > 1.0 } ?: DEFAULT_FPS)).toLong()
        return daemon(
            { read(hnd, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame, getBrightness, onEos) },
            "MediaPlayer-video",
        ).also { it.start() }
    }

    /**
     * Opens an in-process libav session for [url] (no FFmpeg process, no pipe) and starts the
     * reader thread. Requires the planar GPU path. Returns the running thread, or null when
     * the session could not be opened (the caller falls back to the process pipeline).
     */
    fun startInProcess(url: String, w: Int, h: Int, seekOffsetNanos: Long, sourceFps: Double, hwAccel: HwAccelBackend,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ): Thread? {
        if (!planarOutput) return null
        release()
        clear()
        expectedW = w
        expectedH = h
        lastFrameReceivedNanos.set(System.nanoTime())

        val hnd = NativeMedia.lavOpen(url, w, h, seekOffsetNanos / 1_000L, lavHwCode(hwAccel))
        if (hnd == 0L) {
            logger.warn("$debugLabel In-process libav session failed to open; falling back to FFmpeg.")
            return null
        }
        lavHandle = hnd
        val frameNs = (1_000_000_000.0 / (sourceFps.takeIf { it > 1.0 } ?: DEFAULT_FPS)).toLong()
        return daemon(
            { read(0L, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame, getBrightness, onEos) },
            "MediaPlayer-video",
        ).also { it.start() }
    }

    /**
     * Main loop of the reader thread: blocks in the native library until a converted frame lands
     * in the spare buffer, then paces and publishes it exactly like the JVM pipe.
     */
    private fun read(handle: Long, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ) {
        // Planar mode carries I420 (w*h Y + two quarter-size chroma planes); brightness is
        // applied in the fragment shader instead of a CPU LUT pass.
        val frameSize = if (planarOutput) {
            val c = ((w + 1) / 2) * ((h + 1) / 2)
            w * h + 2 * c
        } else {
            w * h * outputFormat.bytesPerPixel
        }
        var spare = surface.takeOrAllocate(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var firstFrame = false
        var videoPts = seekOffsetNanos
        var lavPtsBiasNanos: Long? = null
        var rc = NativeMedia.READ_OK

        val lav = handle == 0L && lavHandle != 0L
        val prerollMarginNs = maxOf(frameNs * 2L, LAV_PREROLL_MARGIN_NS)
        val metrics = NativeReadMetrics(debugLabel, if (lav) "lav" else "process", w, h, frameSize)
        while (!terminated.get() && !stopFlag.get()) {
            spare.clear()
            val readStartNs = System.nanoTime()
            var rawLavPtsNanos = NativeMedia.LAV_NO_PTS_NANOS
            rc = if (lav) {
                val read = NativeMedia.lavReadFrameI420WithPts(lavHandle, spare, frameSize)
                rawLavPtsNanos = read.ptsNanos
                read.code
            } else if (planarOutput) {
                NativeMedia.videoReadFrameI420(handle, spare, frameSize)
            } else {
                val brightnessMilli = (getBrightness().coerceIn(0.0, 2.0) * 1000).toInt()
                if (outputFormat == UploadPixelFormat.RGBA32) {
                    NativeMedia.videoReadFrameRgba(handle, spare, frameSize, brightnessMilli)
                } else {
                    NativeMedia.videoReadFrame(handle, spare, frameSize, brightnessMilli)
                }
            }
            val readElapsedNs = System.nanoTime() - readStartNs
            if (MediaPlayer.DEBUG) metrics.recordRead(readElapsedNs, rc)
            if (rc != NativeMedia.READ_OK) break
            spare.limit(frameSize).position(0)

            lastFrameReceivedNanos.set(System.nanoTime())

            val hasLavPts = lav && rawLavPtsNanos != NativeMedia.LAV_NO_PTS_NANOS
            val framePts = if (hasLavPts) {
                val bias = lavPtsBiasNanos ?: run {
                    val delta = seekOffsetNanos - rawLavPtsNanos
                    val computed = if (delta > LAV_PTS_ORIGIN_TOLERANCE_NS || delta < -LAV_PTS_ORIGIN_TOLERANCE_NS) {
                        delta
                    } else {
                        0L
                    }
                    lavPtsBiasNanos = computed
                    if (computed != 0L && MediaPlayer.DEBUG) {
                        logger.info(
                            "$debugLabel LAV PTS origin shifted by ${"%.1f".format(computed / 1_000_000.0)}ms " +
                                    "(firstPts=${"%.1f".format(rawLavPtsNanos / 1_000_000.0)}ms, " +
                                    "seek=${"%.1f".format(seekOffsetNanos / 1_000_000.0)}ms).",
                        )
                    }
                    computed
                }
                rawLavPtsNanos + bias
            } else {
                videoPts
            }
            if (hasLavPts && framePts + frameNs < seekOffsetNanos - prerollMarginNs) {
                if (MediaPlayer.DEBUG) {
                    MediaPlayer.framesDropped.incrementAndGet()
                    metrics.recordPrerollDrop(seekOffsetNanos - framePts)
                    metrics.maybeLog()
                }
                videoPts = framePts + frameNs
                continue
            }

            val audioClock = getAudioClock()
            val avDiffNs = if (audioClock >= 0) framePts - audioClock else 0L
            if (FramePacing.pace(framePts, audioClock)) {
                if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
                if (MediaPlayer.DEBUG) metrics.recordPacedDrop(avDiffNs)
                videoPts = framePts + frameNs
                if (MediaPlayer.DEBUG) metrics.maybeLog()
                continue
            }
            if (MediaPlayer.DEBUG) metrics.recordPaced(avDiffNs)

            popoutFrameSink?.let { sink ->
                val popoutStartNs = if (MediaPlayer.DEBUG) System.nanoTime() else 0L
                if (planarOutput) {
                    // The popout window consumes RGBA; convert the planar frame natively.
                    val rgbaSize = w * h * 4
                    val rgba = popoutRgba?.takeIf { it.capacity() >= rgbaSize }
                        ?: surface.allocateFrameBuffer(rgbaSize).also { popoutRgba = it }
                    rgba.clear()
                    if (NativeMedia.i420ToRgba(spare, frameSize, rgba, w, h) == 0) {
                        rgba.limit(rgbaSize).position(0)
                        sink(rgba, w, h, UploadPixelFormat.RGBA32)
                    }
                    spare.rewind()
                } else {
                    sink(spare, w, h, outputFormat); spare.rewind()
                }
                if (MediaPlayer.DEBUG) metrics.recordPopout(System.nanoTime() - popoutStartNs)
            }

            if (!MediaPlayer.captureSamples) {
                if (MediaPlayer.DEBUG) metrics.recordNotPublished()
                videoPts = framePts + frameNs
                if (MediaPlayer.DEBUG) metrics.maybeLog()
                continue
            }

            spare = surface.publish(spare, frameSize)
            if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()
            if (MediaPlayer.DEBUG) metrics.recordPublished()
            if (!firstFrame) {
                firstFrame = true
                onFirstFrame()
                if (MediaPlayer.DEBUG) logger.info("$debugLabel First frame ${w}x${h} (native).")
            }

            videoPts = framePts + frameNs
            if (MediaPlayer.DEBUG) metrics.maybeLog()
        }

        if (!terminated.get() && !stopFlag.get()) {
            if (MediaPlayer.DEBUG) metrics.logFinal(rc)
            if (lav) {
                onEos(NativeMedia.lavError(lavHandle), rc == NativeMedia.READ_EOF)
            } else {
                val stderr = NativeMedia.videoStderr(handle)
                val normalEos = rc == NativeMedia.READ_EOF
                        && NativeMedia.videoExitCode(handle, EXIT_WAIT_MILLIS) == 0
                onEos(stderr, normalEos)
            }
        }
    }

    /**
     * Kills the FFmpeg process, unblocking a reader stuck in the native read call.
     * The native session itself stays alive until [release].
     */
    fun kill() {
        val h = handle
        if (h != 0L) NativeMedia.videoKill(h)
        val lh = lavHandle
        if (lh != 0L) NativeMedia.lavKill(lh)
    }

    /**
     * Frees the native session. Must only be called after the reader thread has been
     * joined (the handle must not be in use). Safe to call repeatedly.
     */
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeMedia.videoClose(h)
        val lh = lavHandle
        lavHandle = 0L
        if (lh != 0L) NativeMedia.lavClose(lh)
    }

    /**
     * LAV defaults to native-side auto probing for a non-NONE hardware request. That lets one
     * binary try platform fallbacks (for example VAAPI then CUDA on Linux) before software decode.
     * `-Ddreamdisplays.native.libav.hw=<backend>` pins it for driver debugging.
     */
    private fun lavHwCode(hwAccel: HwAccelBackend): Int {
        if (hwAccel == HwAccelBackend.NONE) return HwAccelBackend.NONE.lavCode
        return when (System.getProperty("dreamdisplays.native.libav.hw", "auto").lowercase()) {
            "auto" -> LAV_HW_AUTO
            "videotoolbox", "vt" -> HwAccelBackend.VIDEOTOOLBOX.lavCode
            "d3d11va", "d3d11" -> HwAccelBackend.D3D11VA.lavCode
            "vaapi" -> HwAccelBackend.VAAPI.lavCode
            "cuda", "nvdec" -> HwAccelBackend.CUDA.lavCode
            "none", "software", "sw" -> HwAccelBackend.NONE.lavCode
            else -> hwAccel.lavCode
        }
    }

    private class NativeReadMetrics(
        private val debugLabel: String,
        private val source: String,
        private val w: Int,
        private val h: Int,
        private val frameBytes: Int,
    ) {
        private val logger = LoggerFactory.getLogger("DreamDisplays/NativeVideoFramePipe")
        private var lastLogNs = System.nanoTime()
        private var readCount = 0L
        private var readTotalNs = 0L
        private var readMaxNs = 0L
        private var slowReads = 0L
        private var published = 0L
        private var notPublished = 0L
        private var pacedDrops = 0L
        private var prerollDrops = 0L
        private var prerollMaxNs = 0L
        private var paced = 0L
        private var avBehindMaxNs = 0L
        private var avAheadMaxNs = 0L
        private var popoutCount = 0L
        private var popoutTotalNs = 0L
        private var lastRc = NativeMedia.READ_OK

        fun recordRead(elapsedNs: Long, rc: Int) {
            lastRc = rc
            if (rc != NativeMedia.READ_OK) return
            readCount++
            readTotalNs += elapsedNs
            readMaxNs = maxOf(readMaxNs, elapsedNs)
            if (elapsedNs >= SLOW_READ_NS) slowReads++
        }

        fun recordPaced(avDiffNs: Long) {
            paced++
            recordAvDiff(avDiffNs)
        }

        fun recordPacedDrop(avDiffNs: Long) {
            pacedDrops++
            recordAvDiff(avDiffNs)
        }

        fun recordPrerollDrop(offsetNs: Long) {
            prerollDrops++
            prerollMaxNs = maxOf(prerollMaxNs, offsetNs)
        }

        fun recordPopout(elapsedNs: Long) {
            popoutCount++
            popoutTotalNs += elapsedNs
        }

        fun recordPublished() {
            published++
        }

        fun recordNotPublished() {
            notPublished++
        }

        fun maybeLog() {
            val now = System.nanoTime()
            if (readCount < 60 && now - lastLogNs < LOG_INTERVAL_NS) return
            log("diag")
            lastLogNs = now
            reset()
        }

        fun logFinal(rc: Int) {
            lastRc = rc
            if (readCount > 0 || published > 0 || pacedDrops > 0) log("final")
        }

        private fun recordAvDiff(avDiffNs: Long) {
            if (avDiffNs < 0) avBehindMaxNs = maxOf(avBehindMaxNs, -avDiffNs)
            else avAheadMaxNs = maxOf(avAheadMaxNs, avDiffNs)
        }

        private fun log(kind: String) {
            val avgReadMs = if (readCount == 0L) 0.0 else readTotalNs / readCount / 1_000_000.0
            val maxReadMs = readMaxNs / 1_000_000.0
            val popoutAvgMs = if (popoutCount == 0L) 0.0 else popoutTotalNs / popoutCount / 1_000_000.0
            val rt = Runtime.getRuntime()
            val heapUsedMiB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val heapMaxMiB = rt.maxMemory() / (1024 * 1024)
            logger.info(
                "$debugLabel native-$kind source=$source frame=${w}x$h bytes=${"%.2f".format(frameBytes / 1048576.0)}MiB " +
                        "read=${readCount} avgRead=${"%.3f".format(avgReadMs)}ms maxRead=${"%.3f".format(maxReadMs)}ms slowRead=$slowReads " +
                        "published=$published noCapture=$notPublished paced=$paced pacedDrops=$pacedDrops " +
                        "prerollDrops=$prerollDrops prerollMax=${"%.1f".format(prerollMaxNs / 1_000_000.0)}ms " +
                        "avBehindMax=${"%.1f".format(avBehindMaxNs / 1_000_000.0)}ms avAheadMax=${"%.1f".format(avAheadMaxNs / 1_000_000.0)}ms " +
                        "popoutAvg=${"%.3f".format(popoutAvgMs)}ms heap=${heapUsedMiB}/${heapMaxMiB}MiB rc=$lastRc",
            )
        }

        private fun reset() {
            readCount = 0L
            readTotalNs = 0L
            readMaxNs = 0L
            slowReads = 0L
            published = 0L
            notPublished = 0L
            pacedDrops = 0L
            prerollDrops = 0L
            prerollMaxNs = 0L
            paced = 0L
            avBehindMaxNs = 0L
            avAheadMaxNs = 0L
            popoutCount = 0L
            popoutTotalNs = 0L
        }

        private companion object {
            private const val LOG_INTERVAL_NS = 2_000_000_000L
            private const val SLOW_READ_NS = 100_000_000L
        }
    }
}
