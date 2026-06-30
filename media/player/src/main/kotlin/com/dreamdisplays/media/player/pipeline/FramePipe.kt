package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.api.media.player.GpuTextureRef
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicLong

/**
 * Render-facing contract shared by the JVM ([VideoFramePipe]) and native
 * ([NativeVideoFramePipe]) video pipes. Starting and stopping a session stays on the
 * concrete types because their inputs differ (an owned [Process] vs. a native handle).
 */
internal interface FramePipe {
    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */
    val lastFrameReceivedNanos: AtomicLong

    /** Set by the popout window to receive raw frames. Called on the reader thread. */
    var popoutFrameSink: ((ByteBuffer, Int, Int, FramePixelFormat) -> Unit)?

    /** Returns true once a frame is available for upload or has already been uploaded to the GPU texture. */
    fun textureFilled(): Boolean

    /**
     * Uploads the ready frame to [texture] if one is available. Must be called from the render thread.
     * Returns true when a frame was actually uploaded (drives the dual-texture quality handoff).
     */
    fun updateFrame(texture: GpuTextureRef, actualW: Int, actualH: Int): Boolean

    /**
     * Uploads the ready I420 frame into the three plane textures if one is available. Only
     * meaningful for pipes producing planar output (GPU-YUV mode); no-op otherwise.
     * Must be called from the render thread. Returns true when a frame was actually uploaded.
     */
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int): Boolean =
        false

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear()

    /** Drops non-essential raw frame buffers while a session is fully warm-parked. */
    fun trimForPark() = clear()

    /** Releases GPU resources. Must be called from the render thread on permanent shutdown. */
    fun cleanup()
}

/** A/V pacing shared by both frame pipes: sleep until a frame is due, drop when too late. */
internal object FramePacing {
    /** Threshold under which we use busy-wait (spin) instead of sleep for precise timing. */
    private const val SPIN_THRESHOLD_NS = 2L * 1_000_000L

    /** Drop a frame when it's more than 80 ms behind the audio clock. */
    private const val DROP_THRESHOLD_NS = 80_000_000L

    /** Single-sample clock entry point. */
    fun pace(videoPts: Long, audioClock: Long): Boolean = pace(videoPts) { audioClock }

    /**
     * Paces the reader thread against the audio clock: parks/spins until [videoPts] is due, then
     * re-samples the clock so an overslept frame is dropped instead of being presented late.
     * [audioClock] may return -1 when no audio line is open yet.
     */
    fun pace(videoPts: Long, audioClock: () -> Long): Boolean {
        val firstClock = audioClock()
        val diff = videoPts - if (firstClock >= 0) firstClock else videoPts
        waitUntilDue(diff)
        val latestClock = audioClock()
        val latestDiff = videoPts - if (latestClock >= 0) latestClock else videoPts
        return latestDiff < -DROP_THRESHOLD_NS
    }

    private fun waitUntilDue(diff: Long) {
        if (diff > 0) {
            val target = System.nanoTime() + diff
            while (true) {
                val remaining = target - System.nanoTime()
                if (remaining <= 0) break
                if (remaining > SPIN_THRESHOLD_NS) {
                    LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NS)
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt()
                        break
                    }
                } else {
                    Thread.onSpinWait()
                }
            }
        }
    }
}
