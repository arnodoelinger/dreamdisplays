package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 * The very first decoded frame is presented immediately as a preview (before the prefill completes),
 * so starts and seeks show the target picture as soon as one frame exists.
 *
 * Enabled by default ([prefillFrames] is derived from `dreamdisplays.playback.prebufferMs`, default 400 ms);
 * set the property to 0 to keep the original inline pace-and-publish path.
 */
internal class FramePrebuffer(
    private val surface: FrameSurface,
    private val capacityFrames: Int,
    private val prefillFrames: Int,
    private val getAudioClock: () -> Long,
    @Volatile private var onFirstFrame: () -> Unit,
    private val terminated: AtomicBoolean,
    private val stopFlag: AtomicBoolean,
    private val debugLabel: String,
    /** When false, the pre-prime preview frame is suppressed: nothing is presented until pacing lets a
     *  frame through. Off for the quality-switch incoming channel, where presenting the (stale) first
     *  decoded frame would promote a rewound picture that then holds until decode catches the clock. */
    private val presentPreview: Boolean,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FramePrebuffer")

    private class Timed(
        @JvmField val buf: ByteBuffer,
        @JvmField val pts: Long,
        @JvmField val generation: Int,
    )

    private val queue = ArrayBlockingQueue<Timed>(capacityFrames.coerceAtLeast(1))

    /** Set once the producer has finished (normal EOS): the consumer drains the tail then exits. */
    @Volatile
    private var inputClosed = false

    /** Hard stop (teardown): the consumer exits immediately, recycling whatever is queued. */
    @Volatile
    private var aborted = false

    /** True once enough frames are queued (or input closed) to begin playout. */
    @Volatile
    private var primed = false

    /** True once the pre-prime preview frame has been shown (see [consume]). */
    @Volatile
    private var previewPresented = false

    @Volatile
    private var flushRequested = false

    /**
     * Monotonic seek counter. Every [resetForSeek] bumps it; frames are tagged with the current
     * value at submit time. A queued frame whose tag lags the current generation belongs to a
     * superseded timeline and must be recycled without pacing — this is the definitive stale
     * signal, independent of the transient [flushRequested] flag (which is cleared before the
     * new-timeline frames replace the old ones, leaving a small window where a tail queue entry
     * would otherwise slip through against the new audio clock).
     */
    private val generation = AtomicInteger(0)

    private val firstFramePresented = AtomicBoolean(false)
    private var consumer: Thread? = null

    /**
     * Invoked on the consumer thread for each frame right after it passes pacing and before it is
     * presented, with the ready buffer (position 0, limit = frame size). Used to feed paced copies
     * such as the PiP / popout sink so they stay in sync with the in-world display instead of being
     * fed at the faster decode rate. The callback must leave the buffer ready for presentation.
     */
    @Volatile
    var onPresent: ((ByteBuffer) -> Unit)? = null

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
        var blockedSinceNs = 0L
        val gen = generation.get()
        runCatching {
            while (alive() && !flushRequested) {
                if (queue.offer(Timed(frame, pts, gen), POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (!primed && queue.size >= prefillFrames) primed = true
                    logSlowSubmit(blockedSinceNs)
                    return surface.takeOrAllocate(nextSize)
                }
                if (blockedSinceNs == 0L) blockedSinceNs = System.nanoTime()
            }
        }.onFailure { e ->
            if (e !is InterruptedException) throw e
            Thread.currentThread().interrupt()
        }
        logSlowSubmit(blockedSinceNs)
        surface.recycleFrameBuffer(frame)
        return surface.takeOrAllocate(nextSize)
    }

    private fun logSlowSubmit(blockedSinceNs: Long) {
        if (blockedSinceNs == 0L) return
        val blockedMs = (System.nanoTime() - blockedSinceNs) / 1_000_000
        if (blockedMs >= 1_000) {
            logger.warn(
                "$debugLabel Producer blocked ${blockedMs} ms in submit " +
                        "(queue=${queue.size}/$capacityFrames, primed=$primed, flush=$flushRequested)."
            )
        }
    }

    /** Marks the producer finished (normal EOS); the consumer drains the remaining tail, then exits. */
    fun finish() {
        inputClosed = true
        primed = true
    }

    /**
     * Called from the control thread the moment a seek is requested: drops queued pre-seek frames and
     * unblocks both a producer stuck in [submit] and the consumer parked in pacing, so the reader
     * thread reaches its seek check immediately instead of finishing playout of the stale cushion
     * first (which turned every backward seek into a multi-second freeze).
     */
    fun requestFlush() {
        flushRequested = true
        drainAndRecycle()
    }

    /** Drops queued frames and re-primes the same consumer for an in-place decoder seek. */
    fun resetForSeek(onFirstFrame: () -> Unit) {
        generation.incrementAndGet()
        drainAndRecycle()
        inputClosed = false
        primed = false
        previewPresented = false
        firstFramePresented.set(false)
        this.onFirstFrame = onFirstFrame
        flushRequested = false
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
                if (!primed) {
                    // Show the very first decoded frame immediately instead of sitting on a black /
                    // stale picture for the whole prefill: the viewer gets instant visual feedback on
                    // start and seek, while the clock (and audio) still wait for the full cushion.
                    if (presentPreview && !previewPresented && !flushRequested) {
                        val tf = queue.poll()
                        if (tf != null) {
                            previewPresented = true
                            onPresent?.invoke(tf.buf)
                            surface.present(tf.buf)
                            continue
                        }
                    }
                    Thread.sleep(2); continue
                }
                val tf = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (tf == null) {
                    if (inputClosed) break // Tail drained
                    continue
                }
                if (tf.generation != generation.get()) {
                    surface.recycleFrameBuffer(tf.buf)
                    continue
                }
                // Bail out of the pacing wait as soon as a seek flush or teardown is requested;
                // a pre-seek frame must be dropped, never presented late against the new clock.
                // dropStaleTimeline=false: the generation check just above already recycled any
                // frame from a superseded timeline, so a large diff here can only be genuine
                // same-timeline lag (e.g. a game-thread hitch delaying the audio line) — let it
                // wait out / resolve through pace()'s own watchdog instead of being dropped as
                // "stale" on sight, which used to make every subsequent frame drop the same way
                // until a seek reset the audio clock (the picture reads as frozen indefinitely).
                if (FramePacing.pace(tf.pts, getAudioClock, { flushRequested || !alive() }, dropStaleTimeline = false) || flushRequested) {
                    surface.recycleFrameBuffer(tf.buf)
                    continue
                }
                onPresent?.invoke(tf.buf)
                surface.present(tf.buf)
                if (firstFramePresented.compareAndSet(false, true)) {
                    onFirstFrame()
                    if (MediaPlayer.DEBUG)
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
            presentPreview: Boolean = true,
        ): FramePrebuffer? {
            if (!enabled || frameNs <= 0) return null
            val prefill = ((prebufferMs * 1_000_000L) / frameNs).toInt().coerceIn(2, 240)
            // A little slack above the prefill mark so the producer can run slightly ahead of playout.
            // Memory cost is capacity * frameSize, so keep it tight — raw frames are large at high res.
            val capacity = prefill + 4
            // Let the surface pool retain every in-flight buffer (queue + ready + spare) so steady-state
            // playout reuses buffers instead of churning large direct allocations (which would GC-stutter).
            surface.setMaxReusableBuffers(capacity + 2)
            return FramePrebuffer(
                surface,
                capacity,
                prefill,
                getAudioClock,
                onFirstFrame,
                terminated,
                stopFlag,
                debugLabel,
                presentPreview,
            )
                .also { it.start() }
        }
    }
}
