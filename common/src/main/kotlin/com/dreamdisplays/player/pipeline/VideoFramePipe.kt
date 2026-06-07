package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import com.dreamdisplays.render.AsyncTextureUploader
import com.dreamdisplays.render.TextureUploadUtil
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Producer-consumer frame buffer and FFmpeg video reader loop.
 *
 * The reader thread fills a "spare" buffer, then atomically swaps it into [readyBufferRef];
 * the render thread reads from [readyBufferRef] without blocking the reader.
 */
internal class VideoFramePipe(private val debugLabel: String) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/VideoFramePipe")
    companion object {
        /** Default frame rate when the source doesn't report one or reports an invalid one. */
        private const val DEFAULT_FPS = 30.0

        /** Threshold under which we use busy-wait (spin) instead of sleep for precise timing. */
        private const val SPIN_THRESHOLD_NS = 2L * 1_000_000L

        /** Drop a frame when it's more than 80 ms behind the audio clock. */
        private const val DROP_THRESHOLD_NS = 80_000_000L

        private const val MAX_REUSABLE_FRAME_BUFFERS = 4
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    val lastFrameReceivedNanos = AtomicLong(0)

    /** Set by the popout window to receive raw RGB frames. Called on the reader thread. */
    @Volatile var popoutFrameSink: ((ByteBuffer, Int, Int) -> Unit)? = null

    @Volatile var expectedW = 0
        private set
    @Volatile var expectedH = 0
        private set

    private val readyBufferRef = AtomicReference<ByteBuffer?>(null)
    private val textureReady = AtomicBoolean(false)
    private val reusableFrameBuffers = ConcurrentLinkedQueue<ByteBuffer>()

    private var uploader: AsyncTextureUploader? = null
    private var rgbaUploadBuffer: ByteBuffer? = null

    private var uploadTotalNs = 0L
    private var uploadCount = 0

    /**
     * Returns true once a frame is available for upload or has already been uploaded to the GPU texture.
     */
    fun textureFilled(): Boolean = textureReady.get() || readyBufferRef.get() != null

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
    fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int) {
        val buf = readyBufferRef.getAndSet(null) ?: return
        if (actualW != expectedW || actualH != expectedH || Minecraft.getInstance().window.isMinimized) {
            recycleFrameBuffer(buf)
            return
        }
        buf.rewind()
        val start = System.nanoTime()
        try {
            TextureUploadUtil.uploadRgb(
                texture = texture,
                src = buf,
                w = texture.getWidth(0),
                h = texture.getHeight(0),
                glUploader = { uploader ?: AsyncTextureUploader(stateCache = true).also { uploader = it } },
                rgbaScratch = rgbaUploadBuffer,
                setRgbaScratch = { rgbaUploadBuffer = it },
            )
            textureReady.set(true)
            if (MediaPlayer.DEBUG) {
                uploadTotalNs += System.nanoTime() - start
                MediaPlayer.framesToGpu.incrementAndGet()
                if (++uploadCount >= 60) {
                    val avgMs = uploadTotalNs / 60 / 1_000_000.0
                    logger.info("$debugLabel Upload avg. ${String.format("%.3f", avgMs)} ms / frame")
                    uploadTotalNs = 0L; uploadCount = 0
                }
            }
        } finally {
            recycleFrameBuffer(buf)
        }
    }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        textureReady.set(false)
        readyBufferRef.getAndSet(null)?.let(::recycleFrameBuffer)
        reusableFrameBuffers.clear()
    }

    /**
     * Releases the PBO ring. Must be called from the render thread when this pipe is permanently
     * discarded (i.e., the owning [MediaPlayer] is being stopped for good).
     */
    fun cleanup() {
        clear()
        uploader?.cleanup()
        uploader = null
        rgbaUploadBuffer = null
    }

    /**
     * Starts the video reader thread and returns it (already running).
     *
     * @param seekOffsetNanos initial playback position (must match the FFmpeg `-ss` offset)
     * @param sourceFps       frame rate reported by yt-dlp for the chosen stream
     * @param getAudioClock   returns current audio position in nanos, or -1 if unavailable
     * @param onFirstFrame    called once when the first frame arrives (starts the wall clock)
     * @param getBrightness   returns current brightness multiplier (read per frame)
     * @param onEos           called when the stream ends with stderr output and EOS flag
     * @param fitTexture      posted to the Minecraft render queue after each frame swap
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
        var spare = allocateFrameBuffer(frameSize)
        recycleFrameBuffer(allocateFrameBuffer(frameSize))

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
                        readyBufferRef.set(null)
                        reusableFrameBuffers.clear()
                        spare = allocateFrameBuffer(frameSize)
                        recycleFrameBuffer(allocateFrameBuffer(frameSize))
                    }

                    if (spare.capacity() < requiredFrameSize) {
                        spare = takeReusableFrameBuffer(requiredFrameSize) ?: allocateFrameBuffer(requiredFrameSize)
                    }
                    spare.clear()
                    if (spare.remaining() < requiredFrameSize) {
                        logger.warn("$debugLabel Reallocated undersized frame buffer: remaining=${spare.remaining()} required=$requiredFrameSize.")
                        spare = allocateFrameBuffer(requiredFrameSize)
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

                    val audioClock = getAudioClock()
                    val diff = videoPts - if (audioClock >= 0) audioClock else videoPts
                    if (diff > 0) {
                        val target = System.nanoTime() + diff
                        if (diff > SPIN_THRESHOLD_NS) {
                            Thread.sleep(diff / 1_000_000)
                        } else {
                            while (System.nanoTime() < target) {
                                Thread.onSpinWait()
                            }
                        }
                    }
                    if (diff < -DROP_THRESHOLD_NS) {
                        if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
                        videoPts += frameNs
                        continue
                    }

                    popoutFrameSink?.let { sink -> sink(spare, w, h); spare.rewind() }

                    if (!MediaPlayer.captureSamples) { videoPts += frameNs; continue }

                    val published = spare
                    val dropped = readyBufferRef.getAndSet(published)
                    if (dropped !== published) dropped?.let(::recycleFrameBuffer)
                    spare = takeReusableFrameBuffer(requiredFrameSize) ?: allocateFrameBuffer(requiredFrameSize)
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

    /** Allocate a new direct `ByteBuffer` of at least [size] bytes. The caller is responsible for recycling it when done. */
    private fun allocateFrameBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())

    /**
     * Takes and returns a reusable frame buffer of at least [requiredSize] bytes, or null if none are available.
     * The caller is responsible for clearing and recycling the returned buffer when done.
     */
    private fun takeReusableFrameBuffer(requiredSize: Int): ByteBuffer? {
        while (true) {
            val buffer = reusableFrameBuffers.poll() ?: return null
            if (buffer.capacity() >= requiredSize) {
                buffer.clear()
                return buffer
            }
        }
    }

    /**
     * Recycles [buffer] for future reuse. The buffer will be cleared before reuse, but the caller must ensure it's not
     * currently in use (e.g., by the render thread).
     */
    private fun recycleFrameBuffer(buffer: ByteBuffer) {
        buffer.clear()
        if (reusableFrameBuffers.size < MAX_REUSABLE_FRAME_BUFFERS) {
            reusableFrameBuffers.offer(buffer)
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
