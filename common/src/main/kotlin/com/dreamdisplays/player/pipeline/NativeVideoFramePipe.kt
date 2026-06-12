package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.nativebridge.NativeMedia
import com.dreamdisplays.player.util.daemon
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
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    override val lastFrameReceivedNanos = AtomicLong(0)

    /** Set by the popout window to receive raw frames. Called on the reader thread. */
    @Volatile override var popoutFrameSink: ((ByteBuffer, Int, Int, UploadPixelFormat) -> Unit)? = null

    @Volatile var expectedW = 0
        private set
    @Volatile var expectedH = 0
        private set

    private val outputFormat: UploadPixelFormat =
        if (NativeMedia.rgbaFramesEnabled) UploadPixelFormat.RGBA32 else UploadPixelFormat.RGB24
    private val surface = FrameSurface(debugLabel, outputFormat)

    @Volatile private var handle = 0L

    override fun textureFilled(): Boolean = surface.textureFilled()

    override fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int) =
        surface.updateFrame(texture, actualW, actualH, expectedW, expectedH)

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
     * Main loop of the reader thread: blocks in the native library until a converted frame lands
     * in the spare buffer, then paces and publishes it exactly like the JVM pipe.
     */
    private fun read(handle: Long, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit,
    ) {
        val frameSize = w * h * outputFormat.bytesPerPixel
        var spare = surface.takeOrAllocate(frameSize)
        surface.recycleFrameBuffer(surface.allocateFrameBuffer(frameSize))

        var firstFrame = false
        var videoPts = seekOffsetNanos
        var rc = NativeMedia.READ_OK

        while (!terminated.get() && !stopFlag.get()) {
            spare.clear()
            val brightnessMilli = (getBrightness().coerceIn(0.0, 2.0) * 1000).toInt()
            rc = if (outputFormat == UploadPixelFormat.RGBA32) {
                NativeMedia.videoReadFrameRgba(handle, spare, frameSize, brightnessMilli)
            } else {
                NativeMedia.videoReadFrame(handle, spare, frameSize, brightnessMilli)
            }
            if (rc != NativeMedia.READ_OK) break
            spare.limit(frameSize).position(0)

            lastFrameReceivedNanos.set(System.nanoTime())
            if (!firstFrame) {
                firstFrame = true
                onFirstFrame()
                if (MediaPlayer.DEBUG) logger.info("$debugLabel First frame ${w}x${h} (native).")
            }

            if (FramePacing.pace(videoPts, getAudioClock())) {
                if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
                videoPts += frameNs
                continue
            }

            popoutFrameSink?.let { sink -> sink(spare, w, h, outputFormat); spare.rewind() }

            if (!MediaPlayer.captureSamples) { videoPts += frameNs; continue }

            spare = surface.publish(spare, frameSize)
            if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()

            videoPts += frameNs
        }

        if (!terminated.get() && !stopFlag.get()) {
            val stderr = NativeMedia.videoStderr(handle)
            val normalEos = rc == NativeMedia.READ_EOF
                    && NativeMedia.videoExitCode(handle, EXIT_WAIT_MILLIS) == 0
            onEos(stderr, normalEos)
        }
    }

    /**
     * Kills the FFmpeg process, unblocking a reader stuck in the native read call.
     * The native session itself stays alive until [release].
     */
    fun kill() {
        val h = handle
        if (h != 0L) NativeMedia.videoKill(h)
    }

    /**
     * Frees the native session. Must only be called after the reader thread has been
     * joined (the handle must not be in use). Safe to call repeatedly.
     */
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) NativeMedia.videoClose(h)
    }
}
