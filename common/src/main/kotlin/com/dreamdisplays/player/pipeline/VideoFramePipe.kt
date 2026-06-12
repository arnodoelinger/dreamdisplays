package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.render.UploadPixelFormat
import com.mojang.blaze3d.textures.GpuTexture
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Producer-consumer frame buffer and FFmpeg video reader loop (pure-JVM pipeline).
 *
 * The reader thread parses PPM frames from the FFmpeg pipe, fills a "spare" buffer, then
 * atomically swaps it into the shared [FrameSurface]; the render thread reads from the
 * surface without blocking the reader.
 */
internal class VideoFramePipe(private val debugLabel: String) : FramePipe {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoFramePipe")
    companion object {
        /** Default frame rate when the source doesn't report one or reports an invalid one. */
        private const val DEFAULT_FPS = 30.0
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    override val lastFrameReceivedNanos = AtomicLong(0)

    /** Set by the popout window to receive raw RGB frames. Called on the reader thread. */
    @Volatile override var popoutFrameSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? = null

    @Volatile var expectedW = 0
        private set
    @Volatile var expectedH = 0
        private set

    private val surface = FrameSurface(debugLabel)

    /**
     * Returns true once a frame is available for upload or has already been uploaded to the GPU texture.
     */
    override fun textureFilled(): Boolean = surface.textureFilled()

    /**
     * Uploads the ready frame to [texture] if one is available.
     * [actualW] / [actualH] must match the dimensions this pipe was started with.
     *
     * Warning: this is one of the most expensive operations in the pipeline. It's critical to call this as soon as
     * possible after [textureFilled] returns true, to minimize the chance of the reader thread overwriting the ready
     * buffer before upload.
     *
     * You should also never call this method more than once per frame, or call it when [textureFilled] is false.
     * It does not block or wait for a frame to be ready, and it does not guarantee that the same frame will still be
     * ready by the time it executes.
     */
    override fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int) =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

    /** Discards the current ready frame. Call when stopping or seeking. */
    override fun clear() = surface.clear()

    /**
     * Releases the PBO ring. Must be called from the render thread when this pipe is permanently
     * discarded (i.e., the owning [MediaPlayer] is being stopped for good).
     */
    override fun cleanup() = surface.cleanup()

    /**
     * Starts the video reader thread and returns it (already running).
     *
     * @param seekOffsetNanos initial playback position (must match the FFmpeg `-ss` offset)
     * @param sourceFps       frame rate reported by yt-dlp for the chosen stream
     * @param getAudioClock   returns current audio position in nanos, or -1 if unavailable
     * @param onFirstFrame    called once when the first frame arrives (starts the wall clock)
     * @param getBrightness   returns current brightness multiplier (read per frame)
     * @param onEos           called when the stream ends with stderr output and EOS flag
     */
    fun start(proc: Process, w: Int, h: Int, seekOffsetNanos: Long, sourceFps: Double, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ): Thread {
        clear()
        expectedW = w
        expectedH = h
        lastFrameReceivedNanos.set(System.nanoTime())
        val frameNs = (1_000_000_000.0 / (sourceFps.takeIf { it > 1.0 } ?: DEFAULT_FPS)).toLong()
        return daemon(
            { read(proc, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame, getBrightness, onEos) },
            "MediaPlayer-video",
        ).also { it.start() }
    }

    /**
     * Main loop of the video reader thread. Reads raw RGB frames from [proc], applies brightness, and fills the ready buffer.
     */
    private fun read(proc: Process, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ) {
        var frameSize = w * h * 3
        var spare = surface.allocateFrameBuffer(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var firstFrame = false
        var videoPts = seekOffsetNanos

        val stderrBuf = StringBuilder()
        val stderrThread = daemon({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { line ->
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n') }
                        if (MediaUtil.isInterestingStderr(line)) {
                            logger.warn("$debugLabel FFmpeg[V] $line")
                        }
                    }
                }
            } catch (_: IOException) {}
        }, "MediaPlayer-vstderr").also { it.start() }

        var normalEos = false

        try {
            proc.inputStream.use { input ->
                var rowBuf = ByteArray(w * 3)
                while (!terminated.get() && !stopFlag.get()) {
                    if (!skipToP6(input)) { normalEos = true; break }
                    val pw = readAsciiInt(input)
                    val ph = readAsciiInt(input)
                    val maxVal = readAsciiInt(input)
                    if (pw != w || ph != h || maxVal != 255) {
                        logger.warn("$debugLabel PPM header mismatch: ${pw}x$ph max=$maxVal (expected ${w}x$h max=255).")
                        val skip = pw.toLong() * ph * 3
                        if (pw <= 0 || ph <= 0 || skip <= 0 || !skipBytes(input, skip)) { normalEos = true; break }
                        continue
                    }
                    val requiredFrameSize = w * h * 3
                    if (rowBuf.size < w * 3) rowBuf = ByteArray(w * 3)
                    if (frameSize != requiredFrameSize
                        || spare.capacity() < requiredFrameSize
                    ) {
                        frameSize = requiredFrameSize
                        surface.resetPool()
                        spare = surface.allocateFrameBuffer(frameSize)
                        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))
                    }

                    if (spare.capacity() < requiredFrameSize) {
                        spare = surface.takeOrAllocate(requiredFrameSize)
                    }
                    spare.clear()
                    if (spare.remaining() < requiredFrameSize) {
                        logger.warn("$debugLabel Reallocated undersized frame buffer: remaining=${spare.remaining()} required=$requiredFrameSize.")
                        spare = surface.allocateFrameBuffer(requiredFrameSize)
                        spare.clear()
                    }
                    if (!readFully(input, spare, rowBuf, requiredFrameSize)) { normalEos = true; break }
                    applyBrightness(spare, requiredFrameSize, getBrightness())
                    spare.flip()

                    lastFrameReceivedNanos.set(System.nanoTime())
                    if (!firstFrame) {
                        firstFrame = true
                        onFirstFrame()
                        if (MediaPlayer.DEBUG) logger.info("$debugLabel First frame ${w}x${h}.")
                    }

                    if (FramePacing.pace(videoPts, getAudioClock())) {
                        if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
                        videoPts += frameNs
                        continue
                    }

                    popoutFrameSink?.let { sink -> sink(spare, w, h, UploadPixelFormat.RGB24); spare.rewind() }

                    if (!MediaPlayer.captureSamples) { videoPts += frameNs; continue }

                    spare = surface.publish(spare, requiredFrameSize)
                    if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()

                    videoPts += frameNs
                }
            }
        } catch (e: IOException) {
            if (MediaPlayer.DEBUG && !terminated.get() && !stopFlag.get()) {
                logger.warn("$debugLabel Read: ${e.message}")
            }
        }

        var exitCode = -1
        if (normalEos) {
            try {
                val done = proc.waitFor(500, TimeUnit.MILLISECONDS)
                exitCode = if (done) proc.exitValue() else -1
                if (!done) proc.destroyForcibly()
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }

        if (!terminated.get() && !stopFlag.get()) {
            try { stderrThread.join(500) } catch (_: InterruptedException) {}
            val stderr = synchronized(stderrBuf) { stderrBuf.toString() }
            onEos(stderr, exitCode == 0)
        }
    }

    /** Scans [input] forward until the PPM magic `P6` is consumed (followed by whitespace). Returns false on EOF. */
    private fun skipToP6(input: InputStream): Boolean {
        var state = 0 // 0: looking for 'P', 1: saw 'P', expecting '6'
        while (true) {
            val b = input.read()
            if (b < 0) return false
            when (state) {
                0 -> if (b == 'P'.code) state = 1
                1 -> state = if (b == '6'.code) {
                    val w = input.read()
                    if (w < 0) return false
                    if (isWhitespace(w)) return true
                    if (w == 'P'.code) 1 else 0
                } else if (b == 'P'.code) 1 else 0
            }
        }
    }

    /** Reads an ASCII unsigned int from PPM header, skipping leading whitespace and `#` comments. */
    private fun readAsciiInt(input: InputStream): Int {
        var b = input.read()
        while (b >= 0) {
            if (b == '#'.code) {
                while (b >= 0 && b != '\n'.code) b = input.read()
                b = input.read()
            } else if (isWhitespace(b)) {
                b = input.read()
            } else break
        }
        if (b < 0 || b < '0'.code || b > '9'.code) return -1
        var n = 0
        while (b in '0'.code..'9'.code) {
            n = n * 10 + (b - '0'.code)
            b = input.read()
            if (b < 0) break
        }
        return n
    }

    private fun isWhitespace(b: Int): Boolean = b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code

    /** Reads exactly [size] bytes from [input] into [dst] using [tmp] as a scratch buffer. Returns false on premature EOF. */
    private fun readFully(input: InputStream, dst: ByteBuffer, tmp: ByteArray, size: Int): Boolean {
        if (dst.remaining() < size) {
            logger.warn("$debugLabel Frame buffer is too small: remaining=${dst.remaining()} required=$size.")
            return false
        }
        var remaining = size
        while (remaining > 0) {
            val want = minOf(tmp.size, remaining, dst.remaining())
            if (want <= 0) {
                logger.warn("$debugLabel Frame buffer overflow guard hit: remaining=$remaining capacity=${dst.capacity()} limit=${dst.limit()}.")
                return false
            }
            val n = input.read(tmp, 0, want)
            if (n < 0) return false
            dst.put(tmp, 0, n)
            remaining -= n
        }
        return true
    }

    /**
     * Applies brightness adjustment in-place to the RGB frame in [buf]. [brightness] is a multiplier where 1.0 means no change, <1.0 is darker, and >1.0 is brighter.
     */
    private fun applyBrightness(buf: ByteBuffer, size: Int, brightness: Double) {
        val factor = brightness.coerceIn(0.0, 2.0)
        if (factor == 1.0) return

        val savedPosition = buf.position()
        val savedLimit = buf.limit()
        buf.flip()
        for (i in 0 until size) {
            val value = ((buf.get(i).toInt() and 0xFF) * factor).toInt().coerceIn(0, 255)
            buf.put(i, value.toByte())
        }
        buf.limit(savedLimit)
        buf.position(savedPosition)
    }

    /** Drops exactly [size] bytes from [input]. Returns false on premature EOF. */
    private fun skipBytes(input: InputStream, size: Long): Boolean {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return false
                remaining -= 1
            } else remaining -= skipped
        }
        return true
    }
}
