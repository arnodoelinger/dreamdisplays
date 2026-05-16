package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.MediaPlayer
import com.dreamdisplays.player.util.MediaBufferEffects
import com.dreamdisplays.player.util.MediaUtil
import com.dreamdisplays.player.util.daemon
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Producer-consumer frame buffer and FFmpeg video reader loop.
 *
 * The reader thread fills a "spare" buffer, then atomically swaps it into [readyBufferRef];
 * the render thread reads from [readyBufferRef] without blocking the reader.
 */
internal class VideoFramePipe(private val debugLabel: String) {

    companion object {
        /** Default frame rate when the source doesn't report one or reports an invalid one. */
        private const val DEFAULT_FPS = 30.0

        /** Park the video thread when it's more than 10 ms ahead of the audio clock. */
        private const val SYNC_THRESHOLD_NS = 10_000_000L

        /** Drop a frame when it's more than 80 ms behind the audio clock. */
        private const val DROP_THRESHOLD_NS = 80_000_000L
    }

    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    val lastFrameReceivedNanos = AtomicLong(0)

    @Volatile var expectedW = 0
        private set
    @Volatile var expectedH = 0
        private set

    private val readyBufferRef = AtomicReference<ByteBuffer?>(null)
    private val frameAvailable = AtomicBoolean(false)

    /**
     * Returns true when a frame is available for upload. The render thread should call [updateFrame] as soon as possible
     * after this returns true, to minimize the chance of the reader thread overwriting the ready buffer before upload.
     */
    fun textureFilled(): Boolean = readyBufferRef.get() != null

    /**
     * Uploads the ready frame to [texture] if one is available.
     * [actualW] / [actualH] must match the dimensions this pipe was started with.
     */
    fun updateFrame(texture: GpuTexture, actualW: Int, actualH: Int) {
        if (!frameAvailable.compareAndSet(true, false)) return
        val buf = readyBufferRef.get() ?: return
        if (actualW != expectedW || actualH != expectedH) return
        buf.rewind()
        if (!texture.isClosed) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture, buf, NativeImage.Format.RGBA,
                0, 0, 0, 0, texture.getWidth(0), texture.getHeight(0),
            )
        }
        if (MediaPlayer.DEBUG) MediaPlayer.framesToGpu.incrementAndGet()
    }

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear() {
        frameAvailable.set(false)
        readyBufferRef.set(null)
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
        onEos: (stderr: String, normalEos: Boolean) -> Unit, fitTexture: Runnable,
    ): Thread {
        expectedW = w
        expectedH = h
        val frameNs = (1_000_000_000.0 / (sourceFps.takeIf { it > 1.0 } ?: DEFAULT_FPS)).toLong()
        return daemon(
            { read(proc, w, h, frameNs, seekOffsetNanos, stopFlag, terminated, getAudioClock, onFirstFrame, getBrightness, onEos, fitTexture) },
            "MediaPlayer-video",
        ).also { it.start() }
    }

    /**
     * Main loop of the video reader thread. Reads raw RGBA frames from [proc], applies brightness, and fills the ready buffer.
     */
    private fun read(proc: Process, w: Int, h: Int, frameNs: Long, seekOffsetNanos: Long, stopFlag: AtomicBoolean,
        terminated: AtomicBoolean, getAudioClock: () -> Long, onFirstFrame: () -> Unit, getBrightness: () -> Double,
        onEos: (stderr: String, normalEos: Boolean) -> Unit, fitTexture: Runnable,
    ) {
        val frameSize = w * h * 4
        val frameData = ByteArray(frameSize)
        val bufA = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder())
        val bufB = ByteBuffer.allocateDirect(frameSize).order(ByteOrder.nativeOrder())
        var spare: ByteBuffer = bufA

        var firstFrame = false
        var videoPts = seekOffsetNanos

        val stderrBuf = StringBuilder()
        val stderrThread = daemon({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.lineSequence().forEach { line ->
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n') }
                        if (MediaUtil.isInterestingStderr(line)) {
                            LoggingManager.warn("[FFmpeg[V] $debugLabel] $line")
                        }
                    }
                }
            } catch (_: IOException) {}
        }, "MediaPlayer-vstderr").also { it.start() }

        var normalEos = false
        val mc = Minecraft.getInstance()

        try {
            proc.inputStream.use { input ->
                while (!terminated.get() && !stopFlag.get()) {
                    val n = MediaUtil.readFull(input, frameData, frameSize)
                    if (n < frameSize) { normalEos = true; break }

                    lastFrameReceivedNanos.set(System.nanoTime())
                    if (!firstFrame) {
                        firstFrame = true
                        onFirstFrame()
                        if (MediaPlayer.DEBUG) LoggingManager.info("[VideoFramePipe $debugLabel] First frame ${w}x${h}.")
                    }

                    val audioClock = getAudioClock()
                    val diff = videoPts - if (audioClock >= 0) audioClock else videoPts
                    if (diff > SYNC_THRESHOLD_NS) LockSupport.parkNanos(diff)
                    if (diff < -DROP_THRESHOLD_NS) {
                        if (MediaPlayer.DEBUG) MediaPlayer.framesDropped.incrementAndGet()
                        videoPts += frameNs
                        continue
                    }

                    if (!MediaPlayer.captureSamples) { videoPts += frameNs; continue }

                    val br = getBrightness()
                    if (br != 1.0) MediaBufferEffects.applyBrightness(frameData, frameSize, br)

                    spare.clear()
                    spare.put(frameData, 0, frameSize)
                    spare.flip()

                    val prev = readyBufferRef.getAndSet(spare)
                    spare = when {
                        prev === bufA || prev === bufB -> prev
                        spare === bufA -> bufB
                        else -> bufA
                    }
                    frameAvailable.set(true)
                    if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()
                    mc.execute(fitTexture)

                    videoPts += frameNs
                }
            }
        } catch (e: IOException) {
            if (MediaPlayer.DEBUG && !terminated.get() && !stopFlag.get()) {
                LoggingManager.warn("[VideoFramePipe $debugLabel] Read: ${e.message}")
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
}
