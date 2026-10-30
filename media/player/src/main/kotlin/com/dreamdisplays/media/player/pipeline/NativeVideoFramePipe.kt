package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.model.FramePixelFormat
import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.media.player.process.HwAccelBackend
import com.dreamdisplays.media.player.process.MediaProcess
import com.dreamdisplays.media.player.util.MediaUtil.ms
import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.util.OsInfo
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native (Rust) variant of [VideoFramePipe]: in-process LAV decoder via Rust FFI.
 */
internal class NativeVideoFramePipe(
    private val debugLabel: String,
    uploaderFactory: FrameUploaderFactory,
    private val planarOutput: Boolean,
) : FramePipe {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val EXIT_WAIT_MILLIS = 500
        internal const val PARK_POLL_MS = 10L
        private const val LAV_HW_AUTO = 1
        private const val LAV_PTS_ORIGIN_TOLERANCE_NS = 10_000_000_000L
        private const val LAV_PREROLL_MARGIN_NS = 50_000_000L
        private const val LAV_PTS_DISCONTINUITY_NS = 1_000_000_000L

        // Rolling packet cache, enabled by default: ~60 s of recent stream. Serves in-window seeks
        // network-free (instant backward scrubbing) and lets a returning display replay locally
        // while the live source re-resolves. Capped by bytes, so the window costs no extra memory
        // on high-bitrate sources.
        private const val CACHE_WINDOW_MS = 60_000L
        private const val CACHE_MAX_BYTES = 96L * 1024L * 1024L
    }

    override val lastFrameReceivedNanos = AtomicLong(0)

    @Volatile
    private var rawPopoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)? = null
    private val lastFrame = LastFrameCache()

    override var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?
        get() = rawPopoutFrameSink
        set(value) {
            rawPopoutFrameSink = value
            if (value != null) lastFrame.replay(value)
        }

    @Volatile
    var expectedW = 0; private set

    @Volatile
    var expectedH = 0; private set

    private val outputFormat = FramePixelFormat.RGBA32
    private val surface = FrameSurface(debugLabel, uploaderFactory, outputFormat)

    @Volatile
    private var activePrebuffer: FramePrebuffer? = null
    private var popoutRgba: ByteBuffer? = null

    @Volatile
    private var handle = 0L

    @Volatile
    private var lavHandle = 0L

    @Volatile
    private var parked: AtomicBoolean? = null

    private class LavSeekCommand(
        val offsetNanos: Long,
        val onFirstFrame: () -> Unit,
        val startGate: (() -> Boolean)?,
    ) {
        val createdNanos: Long = System.nanoTime()

        @Volatile
        var applied = false

        @Volatile
        var failed = false
    }

    /**
     * Offset that puts a raw LAV stream PTS on this pipe's timeline, in nanos, or [Long.MIN_VALUE] before any frame of
     * the current session / in-place seek has arrived. Moves when the stream splices onto another timeline.
     */
    @Volatile
    var rawPtsBiasNanos: Long = Long.MIN_VALUE; private set

    private val lavSeekMonitor = Any()

    @Volatile
    private var pendingLavSeek: LavSeekCommand? = null
    private var readerDone = false

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

    override fun updateFramePlanar(
        y: GpuTextureRef,
        u: GpuTextureRef,
        v: GpuTextureRef,
        actualW: Int,
        actualH: Int
    ): Boolean =
        surface.updateFramePlanar(y, u, v, actualW, actualH, expectedW, expectedH)

    override fun clear() = surface.clear()

    override fun trimForPark() {
        activePrebuffer?.trimForPark()
        surface.clear()
        popoutRgba = null
    }

    override fun cleanup() = surface.cleanup()

    /** Opens a native `FFmpeg` session and starts the reader thread. */
    fun start(
        args: List<String>, w: Int, h: Int, seekOffsetNanos: Long, sourceFps: Double,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean? = null, presentPreview: Boolean = true, tolerateLateness: Boolean = true,
    ): Thread? {
        prepare(w, h, parkFlag)
        handle = NativeMedia.videoOpen(args, w, h).takeIf { it != 0L } ?: run {
            logger.error("$debugLabel Native FFmpeg session failed to start.")
            return null
        }
        return launch(
            "MediaPlayer-video", w, h, sourceFps, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame,
            getBrightness, onEos, parkFlag, presentPreview, tolerateLateness,
        )
    }

    /**
     * Opens an in-process libav session for [url] (no FFmpeg process, no pipe) and starts the
     * reader thread. Requires the planar GPU path. Returns the running thread, or null when
     * the session could not be opened (the caller falls back to the process pipeline).
     */
    fun startInProcess(
        url: String, w: Int, h: Int, seekOffsetNanos: Long, sourceFps: Double, hwAccel: HwAccelBackend,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean? = null, presentPreview: Boolean = true, tolerateLateness: Boolean = true,
    ): Thread? {
        if (!planarOutput) return null
        prepare(w, h, parkFlag)
        lavHandle = NativeMedia.lavOpen(url, w, h, seekOffsetNanos / 1_000L, lavHwCode(hwAccel)).takeIf { it != 0L } ?: run {
            logger.warn("$debugLabel In-process libav session failed to open; falling back to FFmpeg.")
            return null
        }
        enableLavCache(lavHandle)
        return launch(
            "MediaPlayer-video", w, h, sourceFps, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame,
            getBrightness, onEos, parkFlag, presentPreview, tolerateLateness,
        )
    }

    /**
     * Opens a local replay session from a packet-ring [snapshot] and starts the reader thread.
     *
     * @return `null` when the snapshot cannot be opened.
     */
    fun startReplay(
        snapshot: ByteArray, w: Int, h: Int, resumeNanos: Long, sourceFps: Double,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ): Thread? {
        if (!planarOutput) return null
        prepare(w, h, parkFlag = null)
        lavHandle = NativeMedia.lavOpenReplay(snapshot, w, h, resumeNanos).takeIf { it != 0L } ?: run {
            logger.warn("$debugLabel Native replay session failed to open.")
            return null
        }
        return launch(
            "MediaPlayer-video-replay", w, h, sourceFps, resumeNanos, stopFlag, terminated, getAudioClock,
            onFirstFrame, getBrightness, onEos, parkFlag = null, presentPreview = true, tolerateLateness = true,
        )
    }

    private fun prepare(w: Int, h: Int, parkFlag: AtomicBoolean?) {
        release()
        clear()
        expectedW = w
        expectedH = h
        parked = parkFlag
        lastFrameReceivedNanos.set(System.nanoTime())
    }

    private fun launch(
        threadName: String, w: Int, h: Int, sourceFps: Double, seekOffsetNanos: Long,
        stopFlag: AtomicBoolean, terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit,
        getBrightness: () -> Double, onEos: (stderr: String, normalEos: Boolean) -> Unit,
        parkFlag: AtomicBoolean?, presentPreview: Boolean, tolerateLateness: Boolean,
    ): Thread {
        val frameNs = (1_000_000_000.0 / MediaProcess.outputFps(sourceFps)).toLong()
        val prebuffer = FramePrebuffer.create(
            surface, frameNs, getAudioClock, onFirstFrame, terminated, stopFlag, debugLabel, presentPreview,
            tolerateLateness, parkFlag, frameBytes = frameBytesFor(w, h),
        ).also { activePrebuffer = it }
        return daemon(
            {
                read(
                    handle, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, onFirstFrame,
                    getBrightness, onEos, prebuffer, presentPreview,
                )
            },
            threadName,
        ).also { it.start() }
    }

    /** Captures the live LAV packet-ring snapshot, or null when no cache data is ready. */
    fun lavCacheSnapshot(positionNanos: Long): ByteArray? {
        val lh = lavHandle
        return if (lh == 0L) null else NativeMedia.lavRingSnapshot(lh, positionNanos)
    }

    /** Seeks the live in-process LAV session without closing the decoder or network context. */
    fun seekInProcess(
        offsetNanos: Long, startGate: (() -> Boolean)? = null, onFirstFrame: () -> Unit,
    ): Boolean {
        val lh = lavHandle
        if (lh == 0L) return false
        val cmd = LavSeekCommand(offsetNanos, onFirstFrame, startGate)
        synchronized(lavSeekMonitor) {
            if (readerDone) return false
            pendingLavSeek?.let {
                it.failed = true
                if (MediaPlayer.DEBUG) {
                    logger.debug(
                        "$debugLabel Coalescing in-place seek: ${ms(it.offsetNanos)}ms " +
                                "superseded by ${ms(offsetNanos)}ms."
                    )
                }
            }
            pendingLavSeek = cmd
            NativeMedia.lavKill(lh)
        }
        activePrebuffer?.requestFlush()
        return true
    }

    private fun frameBytesFor(w: Int, h: Int): Int = if (planarOutput) {
        val c = ((w + 1) / 2) * ((h + 1) / 2)
        w * h + 2 * c
    } else {
        w * h * outputFormat.bytesPerPixel
    }

    private fun read(
        handle: Long, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit, prebuffer: FramePrebuffer, presentPreview: Boolean,
    ) {
        val frameSize = frameBytesFor(w, h)
        var spare = surface.takeOrAllocate(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var firstFrame = false
        var videoPts = seekOffsetNanos
        var currentSeekOffsetNanos = seekOffsetNanos
        var currentOnFirstFrame = onFirstFrame
        var lavPtsBiasNanos: Long? = null
        var prevRawLavPtsNanos: Long? = null
        var rc = NativeMedia.READ_OK
        var passFirstFrameAfterSeek = true

        val lav = handle == 0L && lavHandle != 0L
        val prerollMarginNs = maxOf(frameNs * 2L, LAV_PREROLL_MARGIN_NS)
        val metrics = NativeReadMetrics(debugLabel, if (lav) "lav" else "process", w, h, frameSize)
        prebuffer.onPresent = { buf -> feedPopout(buf, w, h, frameSize, metrics) }

        fun armFirstFrameClock() {
            if (!firstFrame) {
                firstFrame = true
                currentOnFirstFrame()
                if (MediaPlayer.DEBUG) logger.debug("$debugLabel First frame $w x $h (native).")
            }
        }

        fun applyLavSeek(seek: LavSeekCommand): Boolean {
            val queuedMs = (System.nanoTime() - seek.createdNanos) / 1_000_000
            if (queuedMs >= 1_000) {
                logger.warn(
                    "$debugLabel In-place seek to ${ms(seek.offsetNanos)}ms reached the " +
                            "reader only after $queuedMs ms — reader was blocked outside the native read."
                )
            } else if (MediaPlayer.DEBUG) {
                logger.debug("$debugLabel In-place seek applied $queuedMs ms after request.")
            }
            prebuffer.resetForSeek(seek.onFirstFrame, seek.startGate)
            val ok = NativeMedia.lavSeek(lavHandle, seek.offsetNanos / 1_000L)
            synchronized(lavSeekMonitor) {
                if (ok) seek.applied = true else seek.failed = true
            }
            if (!ok) {
                logger.warn(
                    "$debugLabel In-place LAV seek to ${ms(seek.offsetNanos)}ms failed: " +
                            "${NativeMedia.lavError(lavHandle)}."
                )
                return false
            }
            currentSeekOffsetNanos = seek.offsetNanos
            currentOnFirstFrame = seek.onFirstFrame
            videoPts = seek.offsetNanos
            lavPtsBiasNanos = null
            prevRawLavPtsNanos = null
            rawPtsBiasNanos = Long.MIN_VALUE
            firstFrame = false
            passFirstFrameAfterSeek = true
            lastFrameReceivedNanos.set(System.nanoTime())
            return true
        }

        while (!terminated.get() && !stopFlag.get()) {
            if (lav) {
                val seek = pollLavSeek()
                if (seek != null) {
                    applyLavSeek(seek)
                    continue
                }
            }
            // Parked (display out of render distance): idle without decoding, keeping the native session
            // open so resuming reads the next frame instantly. Refresh the stall timestamp on wake.
            val pk = parked
            if (pk != null && pk.get()) {
                while (pk.get() && !terminated.get() && !stopFlag.get()) {
                    try {
                        Thread.sleep(PARK_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
                lastFrameReceivedNanos.set(System.nanoTime())
                continue
            }
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
                NativeMedia.videoReadFrameRgba(handle, spare, frameSize, brightnessMilli)
            }
            val readElapsedNs = System.nanoTime() - readStartNs
            if (MediaPlayer.DEBUG) metrics.recordRead(readElapsedNs, rc)
            if (lav && rc == NativeMedia.READ_PREVIEW) {
                // Post-seek keyframe delivered ahead of the (network-bound) pre-roll: present it
                // straight to the surface so the picture updates instantly, but never let it
                // start the clock, compute the PTS bias, or enter the prebuffer — its PTS is
                // before the seek target and the exact frame follows once the pre-roll completes.
                lastFrameReceivedNanos.set(System.nanoTime())
                if (presentPreview && MediaPlayer.captureSamples
                    && pendingLavSeek == null && parked?.get() != true
                ) {
                    spare.limit(frameSize).position(0)
                    spare = surface.publish(spare, frameSize)
                }
                continue
            }
            if (lav && rc != NativeMedia.READ_OK) {
                val seek = pollLavSeek()
                if (seek != null && applyLavSeek(seek)) {
                    spare.clear()
                    continue
                }
                if (rc == NativeMedia.READ_INTERRUPTED) {
                    spare.clear()
                    continue
                }
            }
            if (rc != NativeMedia.READ_OK) {
                if (lav && synchronized(lavSeekMonitor) { pendingLavSeek != null || run { readerDone = true; false } }) {
                    spare.clear()
                    continue
                }
                break
            }
            if (parked?.get() == true) {
                lastFrameReceivedNanos.set(System.nanoTime())
                spare.clear()
                continue
            }
            spare.limit(frameSize).position(0)

            lastFrameReceivedNanos.set(System.nanoTime())

            val hasLavPts = lav && rawLavPtsNanos != NativeMedia.LAV_NO_PTS_NANOS
            val framePts = if (hasLavPts) {
                var bias = lavPtsBiasNanos ?: run {
                    val delta = currentSeekOffsetNanos - rawLavPtsNanos
                    val computed = if (delta > LAV_PTS_ORIGIN_TOLERANCE_NS || delta < -LAV_PTS_ORIGIN_TOLERANCE_NS) {
                        delta
                    } else {
                        0L
                    }
                    lavPtsBiasNanos = computed
                    if (computed != 0L && MediaPlayer.DEBUG) {
                        logger.debug(
                            "$debugLabel LAV PTS origin shifted by ${ms(computed)}ms " +
                                    "(firstPts=${ms(rawLavPtsNanos)}ms, " +
                                    "seek=${ms(currentSeekOffsetNanos)}ms).",
                        )
                    }
                    computed
                }
                val prev = prevRawLavPtsNanos
                if (prev != null) {
                    val rawStep = rawLavPtsNanos - prev
                    if (rawStep > LAV_PTS_DISCONTINUITY_NS || rawStep < -LAV_PTS_DISCONTINUITY_NS) {
                        bias = videoPts - rawLavPtsNanos
                        lavPtsBiasNanos = bias
                        if (MediaPlayer.DEBUG) {
                            logger.debug(
                                "$debugLabel LAV PTS discontinuity (rawStep=${ms(rawStep)} ms); " +
                                        "re-anchored video to ${ms(videoPts)} ms.",
                            )
                        }
                    }
                }
                prevRawLavPtsNanos = rawLavPtsNanos
                rawPtsBiasNanos = bias
                rawLavPtsNanos + bias
            } else {
                videoPts
            }
            if (hasLavPts && !passFirstFrameAfterSeek
                && framePts + frameNs < currentSeekOffsetNanos - prerollMarginNs
            ) {
                if (MediaPlayer.DEBUG) {
                    MediaPlayer.framesDropped.incrementAndGet()
                    metrics.recordPrerollDrop(currentSeekOffsetNanos - framePts)
                    metrics.maybeLog()
                }
                videoPts = framePts + frameNs
                continue
            }

            if (passFirstFrameAfterSeek && !MediaPlayer.captureSamples) armFirstFrameClock()
            passFirstFrameAfterSeek = false

            if (MediaPlayer.captureSamples) {
                // The prebuffer arms the clock as its own first frame goes out to pace, so the cushion it
                // filled is not spent as lateness before the first frame is even shown
                spare = prebuffer.submit(spare, framePts, frameSize)
                if (MediaPlayer.DEBUG) {
                    MediaPlayer.samplesIn.incrementAndGet(); metrics.recordPublished()
                }
            } else {
                // Benchmark-only path: nothing is presented, so feed the popout directly
                feedPopout(spare, w, h, frameSize, metrics)
                if (MediaPlayer.DEBUG) metrics.recordNotPublished()
            }
            videoPts = framePts + frameNs
            if (MediaPlayer.DEBUG) metrics.maybeLog()
        }

        synchronized(lavSeekMonitor) { readerDone = true }
        if (!terminated.get() && !stopFlag.get() && rc == NativeMedia.READ_EOF) {
            prebuffer.finish()
        } else {
            prebuffer.abort()
        }
        if (activePrebuffer === prebuffer) activePrebuffer = null

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

    private fun pollLavSeek(): LavSeekCommand? {
        synchronized(lavSeekMonitor) {
            val cmd = pendingLavSeek ?: return null
            pendingLavSeek = null
            return cmd
        }
    }

    private fun feedPopout(spare: ByteBuffer, w: Int, h: Int, frameSize: Int, metrics: NativeReadMetrics) {
        val sink = popoutFrameSink ?: return
        val popoutStartNs = if (MediaPlayer.DEBUG) System.nanoTime() else 0L
        if (planarOutput) {
            // The popout window consumes RGBA; convert the planar frame natively
            val rgbaSize = w * h * 4
            val rgba = popoutRgba?.takeIf { it.capacity() >= rgbaSize }
                ?: surface.allocateFrameBuffer(rgbaSize).also { popoutRgba = it }
            rgba.clear()
            if (NativeMedia.i420ToRgba(spare, frameSize, rgba, w, h) == 0) {
                rgba.limit(rgbaSize).position(0)
                lastFrame.store(rgba, w, h, rgbaSize, FramePixelFormat.RGBA32)
                sink(rgba, w, h, FramePixelFormat.RGBA32)
            }
            spare.rewind()
        } else {
            lastFrame.store(spare, w, h, frameSize, outputFormat)
            sink(spare, w, h, outputFormat); spare.rewind()
        }
        if (MediaPlayer.DEBUG) metrics.recordPopout(System.nanoTime() - popoutStartNs)
    }

    private fun enableLavCache(handle: Long) {
        val maxBytes = FrameMemoryBudget.packetCacheBytesFor(CACHE_MAX_BYTES)
        val ok = NativeMedia.lavEnableCache(handle, CACHE_WINDOW_MS, maxBytes)
        if (MediaPlayer.DEBUG) {
            logger.debug("$debugLabel LAV packet cache ${if (ok) "enabled" else "unavailable"} (maxBytes=$maxBytes).")
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
        parked = null
        synchronized(lavSeekMonitor) {
            pendingLavSeek?.failed = true
            pendingLavSeek = null
        }
        val h = handle
        handle = 0L
        if (h != 0L) NativeMedia.videoClose(h)
        val lh = lavHandle
        lavHandle = 0L
        if (lh != 0L) NativeMedia.lavClose(lh)
    }

    // macOS has always decoded LAV sessions in software
    private fun lavHwCode(hwAccel: HwAccelBackend): Int =
        if (hwAccel == HwAccelBackend.NONE || OsInfo.isMac) HwAccelBackend.NONE.lavCode else LAV_HW_AUTO

    private class NativeReadMetrics(
        private val debugLabel: String,
        private val source: String,
        private val w: Int,
        private val h: Int,
        private val frameBytes: Int,
    ) {
        private val logger = LoggerFactory.getLogger(javaClass)
        private var lastLogNs = System.nanoTime()
        private var readCount = 0L
        private var readTotalNs = 0L
        private var readMaxNs = 0L
        private var slowReads = 0L
        private var published = 0L
        private var notPublished = 0L
        private var prerollDrops = 0L
        private var prerollMaxNs = 0L
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
            if (readCount > 0 || published > 0) log("final")
        }

        private fun log(kind: String) {
            val avgReadMs = if (readCount == 0L) 0.0 else readTotalNs / readCount / 1_000_000.0
            val maxReadMs = readMaxNs / 1_000_000.0
            val popoutAvgMs = if (popoutCount == 0L) 0.0 else popoutTotalNs / popoutCount / 1_000_000.0
            val rt = Runtime.getRuntime()
            val heapUsedMiB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val heapMaxMiB = rt.maxMemory() / (1024 * 1024)
            logger.debug(
                "$debugLabel native-$kind source=$source frame=${w}x$h bytes=${"%.2f".format(frameBytes / 1048576.0)}MiB " +
                        "read=${readCount} avgRead=${"%.3f".format(avgReadMs)}ms maxRead=${"%.3f".format(maxReadMs)}ms slowRead=$slowReads " +
                        "published=$published noCapture=$notPublished " +
                        "prerollDrops=$prerollDrops prerollMax=${ms(prerollMaxNs)}ms " +
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
            prerollDrops = 0L
            prerollMaxNs = 0L
            popoutCount = 0L
            popoutTotalNs = 0L
        }

        private companion object {
            private const val LOG_INTERVAL_NS = 2_000_000_000L
            private const val SLOW_READ_NS = 100_000_000L
        }
    }
}
