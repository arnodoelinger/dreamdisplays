package com.dreamdisplays.player.pipeline

import com.dreamdisplays.player.util.daemon
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional jitter buffer that decouples decoding from playout, eliminating the cold-start / network-stall
 * freeze. Without it both video pipes run a single thread that decodes one frame, paces it (sleeping), and
 * publishes it into the one-slot [FrameSurface]; while that thread is blocked waiting for the next frame
 * (network stall, cold seek) there is nothing new to show and the picture freezes.
 *
 * With a prebuffer the reader thread becomes a pure producer (decode -> [submit], blocking when full so
 * it never runs unbounded), and a dedicated consumer thread paces frames against the audio clock and
 * presents them into the same ready slot. A stall now blocks only the producer; the consumer keeps
 * presenting from the queue, so playback stays smooth until the cushion drains. The audio master clock
 * ([onFirstFrame]) is started only once the queue has pre-filled, so playback begins with a head start.
 *
 * Disabled by default ([prefillFrames] is derived from `dreamdisplays.playback.prebufferMs`, default 0);
 * when off, the pipes keep their original inline pace-and-publish path and this class is never created.
 */
internal class FramePrebuffer(
    private val surface: FrameSurface,
    private val capacityFrames: Int,
    private val prefillFrames: Int,
    private val getAudioClock: () -> Long,
    private val onFirstFrame: () -> Unit,
    private val terminated: AtomicBoolean,
    private val stopFlag: AtomicBoolean,
    private val debugLabel: String,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FramePrebuffer")

    private class Timed(@JvmField val buf: ByteBuffer, @JvmField val pts: Long)

    private val queue = ArrayBlockingQueue<Timed>(capacityFrames.coerceAtLeast(1))

    /** Set once the producer has finished (normal EOS): the consumer drains the tail then exits. */
    @Volatile private var inputClosed = false

    /** Hard stop (teardown): the consumer exits immediately, recycling whatever is queued. */
    @Volatile private var aborted = false

    /** True once enough frames are queued (or input closed) to begin playout. */
    @Volatile private var primed = false

    private val firstFramePresented = AtomicBoolean(false)
    private var consumer: Thread? = null

    /** Starts the consumer thread. Call once, before the first [submit]. */
    fun start() {
        consumer = daemon(::consume, "MediaPlayer-prebuffer-$debugLabel").also { it.start() }
    }

    /**
     * Producer hand-off: enqueues [frame] (tagged with [pts]) for paced playout, blocking while the queue
     * is full so the reader can't outrun playout. Returns a fresh spare buffer of at least [nextSize] bytes
     * for the reader to fill next. On teardown the frame is recycled and a spare returned without blocking.
     */
    fun submit(frame: ByteBuffer, pts: Long, nextSize: Int): ByteBuffer {
        while (!alive()) { /* Fall through to recycle... */ break }
        try {
            while (alive()) {
                if (queue.offer(Timed(frame, pts), POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (!primed && queue.size >= prefillFrames) primed = true
                    return surface.takeOrAllocate(nextSize)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        surface.recycleFrameBuffer(frame)
        return surface.takeOrAllocate(nextSize)
    }

    /** Marks the producer finished (normal EOS); the consumer drains the remaining tail, then exits. */
    fun finish() {
        inputClosed = true
        primed = true
    }

    /** Hard teardown: stops the consumer immediately, recycles queued frames, and joins the thread. */
    fun abort() {
        aborted = true
        consumer?.interrupt()
        consumer?.let { runCatching { it.join(JOIN_MS) } }
        drainAndRecycle()
    }

    /** Drops queued raw frames while keeping the prebuffer alive for a later un-park. */
    fun trimForPark() {
        drainAndRecycle()
        primed = firstFramePresented.get()
    }

    private fun alive(): Boolean = !aborted && !terminated.get() && !stopFlag.get()

    private fun consume() {
        try {
            while (alive()) {
                if (!primed) { Thread.sleep(2); continue }
                val tf = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (tf == null) {
                    if (inputClosed) break // Tail drained
                    continue
                }
                if (FramePacing.pace(tf.pts, getAudioClock())) {
                    surface.recycleFrameBuffer(tf.buf)
                    continue
                }
                surface.present(tf.buf)
                if (firstFramePresented.compareAndSet(false, true)) {
                    onFirstFrame()
                    if (com.dreamdisplays.player.MediaPlayer.DEBUG)
                        logger.debug("$debugLabel First frame presented (prebuffered, prefill=$prefillFrames).")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainAndRecycle()
    }

    private fun drainAndRecycle() {
        while (true) {
            val tf = queue.poll() ?: break
            surface.recycleFrameBuffer(tf.buf)
        }
    }

    companion object {
        private const val POLL_MS = 50L
        private const val JOIN_MS = 500L

        /** Default prebuffer cushion. Smooths cold start / seek / quality-switch; kept under the
         *  TimelineFollower's 1s catch-up tolerance so the added startup latency never reads as drift. */
        private const val DEFAULT_PREBUFFER_MS = 400L

        /** Prebuffer depth in ms. On by default; set `-Ddreamdisplays.playback.prebufferMs=0` to disable. */
        val prebufferMs: Long =
            System.getProperty("dreamdisplays.playback.prebufferMs")?.toLongOrNull()?.coerceIn(0, 5_000)
                ?: DEFAULT_PREBUFFER_MS

        /** True when the prebuffer is enabled and the pipes should route frames through it. */
        val enabled: Boolean get() = prebufferMs > 0

        /**
         * Builds a started prebuffer sized for [frameNs] (one frame's duration), or null when disabled.
         * Capacity is the prefill plus headroom so the producer keeps a little slack above the pre-fill mark.
         */
        fun createIfEnabled(
            surface: FrameSurface, frameNs: Long,
            getAudioClock: () -> Long, onFirstFrame: () -> Unit,
            terminated: AtomicBoolean, stopFlag: AtomicBoolean, debugLabel: String,
        ): FramePrebuffer? {
            if (!enabled || frameNs <= 0) return null
            val prefill = ((prebufferMs * 1_000_000L) / frameNs).toInt().coerceIn(2, 240)
            // A little slack above the prefill mark so the producer can run slightly ahead of playout.
            // Memory cost is capacity * frameSize, so keep it tight — raw frames are large at high res.
            val capacity = prefill + 4
            // Let the surface pool retain every in-flight buffer (queue + ready + spare) so steady-state
            // playout reuses buffers instead of churning large direct allocations (which would GC-stutter).
            surface.setMaxReusableBuffers(capacity + 2)
            return FramePrebuffer(surface, capacity, prefill, getAudioClock, onFirstFrame, terminated, stopFlag, debugLabel)
                .also { it.start() }
        }
    }
}
