package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.media.player.MediaPlayer
import com.dreamdisplays.media.player.util.daemon
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional jitter buffer that decouples decoding from playout, eliminating the cold-start / network-stall freeze by
 * prefilling frames before playout begins.
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
    private val presentPreview: Boolean,
    private val tolerateLateness: Boolean,
    private val parkFlag: AtomicBoolean? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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
     * When the first frame of the current fill was queued, or 0 while none has been. Bounds how long the prefill may
     * hold playout back when the source trickles in slower than real time (see [PRIME_DEADLINE_NANOS]).
     */
    @Volatile
    private var firstSubmitNanos = 0L

    private val generation = AtomicInteger(0)
    private val firstFramePresented = AtomicBoolean(false)
    private var consumer: Thread? = null
    private val pending = AtomicReference<Timed?>(null)

    /**
     * Invoked on the consumer thread for each frame right after it passes pacing and before it is presented, with the raw
     * decoded buffer (e.g. to feed a popout).
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
                    if (firstSubmitNanos == 0L) firstSubmitNanos = System.nanoTime()
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
                "$debugLabel Producer blocked $blockedMs ms in submit " +
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
     * Called from the control thread the moment a seek is requested: drops queued pre-seek frames and unblocks both a
     * stuck producer and consumer.
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
        firstSubmitNanos = 0L
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

    private fun parked(): Boolean = parkFlag?.get() == true

    /** Fires [onFirstFrame] once per fill: the playback clock starts and the audio start gate opens here. */
    private fun armPlayout() {
        if (!firstFramePresented.compareAndSet(false, true)) return
        onFirstFrame()
        if (MediaPlayer.DEBUG) {
            logger.debug("$debugLabel Playout starting (prebuffered, queued=${queue.size}/$prefillFrames).")
        }
    }

    private fun consume() {
        try {
            while (alive()) {
                if (parked()) {
                    Thread.sleep(PARK_POLL_MS); continue
                }
                if (!primed) {
                    // Show the very first decoded frame immediately instead of sitting on a black /
                    // stale picture for the whole prefill: the viewer gets instant visual feedback on
                    // start and seek, while the clock (and audio) still wait for the full cushion.
                    if (presentPreview && !previewPresented && !flushRequested) {
                        val tf = queue.poll()
                        if (tf != null) {
                            // A frame that slipped in between requestFlush's drain and the generation
                            // bump belongs to the pre-seek timeline: showing it as "the" preview would
                            // flash the old position at the viewer right after a seek.
                            if (tf.generation != generation.get()) {
                                surface.recycleFrameBuffer(tf.buf)
                                continue
                            }
                            previewPresented = true
                            onPresent?.invoke(tf.buf)
                            surface.present(tf.buf)
                            continue
                        }
                    }
                    // A source that trickles in slower than real time would otherwise hold the start (and
                    // the audio behind its gate) for as long as it takes to decode the whole cushion.
                    val since = firstSubmitNanos
                    if (since != 0L && System.nanoTime() - since >= PRIME_DEADLINE_NANOS) {
                        primed = true
                        if (MediaPlayer.DEBUG) {
                            logger.debug(
                                "$debugLabel Prefill deadline reached with ${queue.size}/$prefillFrames frames; " +
                                        "starting playout on a short cushion."
                            )
                        }
                        continue
                    }
                    Thread.sleep(2); continue
                }
                val tf = pending.getAndSet(null) ?: queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (tf == null) {
                    if (inputClosed) break // Tail drained
                    continue
                }
                if (tf.generation != generation.get()) {
                    surface.recycleFrameBuffer(tf.buf)
                    continue
                }
                // Playout begins with this frame, so start the clock (and open the audio gate) *before* pacing
                // it, not after presenting it: the cushion just filled is meant to be playout lead, and a clock
                // armed at decode time instead burns all of it as lateness before anything is ever shown — the
                // whole queue then arrives past the drop threshold and the pipe plays on with no cushion at all.
                if (tolerateLateness) armPlayout()
                // Bail out of the pacing wait as soon as a seek flush or teardown is requested;
                // a pre-seek frame must be dropped, never presented late against the new clock.
                // dropStaleTimeline=false: the generation check just above already recycled any
                // frame from a superseded timeline, so a large diff here can only be genuine
                // same-timeline lag (e.g. a game-thread hitch delaying the audio line) — let it
                // wait out / resolve through pace()'s own watchdog instead of being dropped as
                // "stale" on sight, which used to make every subsequent frame drop the same way
                // until a seek reset the audio clock (the picture reads as frozen indefinitely).
                val clockAtHead = getAudioClock()
                val lateNs = if (clockAtHead >= 0L) clockAtHead - tf.pts else 0L
                var abortedByPark = false
                val dropped = FramePacing.pace(
                    tf.pts,
                    getAudioClock,
                    { flushRequested || !alive() || parked().also { if (it) abortedByPark = true } },
                    dropStaleTimeline = false,
                    // Only worth skipping a late frame while a fresher one is already decoded behind it
                    dropWhenBehind = { !tolerateLateness || queue.isNotEmpty() },
                )
                if (abortedByPark && !flushRequested && alive()) {
                    pending.set(tf)
                    continue
                }
                if (dropped || flushRequested) {
                    surface.recycleFrameBuffer(tf.buf)
                    // A drop caused by a flush or a teardown says nothing about A / V health
                    if (dropped && !flushRequested && alive()) recordFrame(lateNs, presentedIt = false)
                    continue
                }
                recordFrame(lateNs, presentedIt = true)
                onPresent?.invoke(tf.buf)
                surface.present(tf.buf)
                armPlayout()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainAndRecycle()
    }

    /**
     * Consumer-thread-only A / V health counters. Pacing guarantees a *presented* frame is within [FramePacing]'s tolerance;
     * these track how often that fails.
     */
    private var statPresented = 0L
    private var statPresentedLate = 0L
    private var statDroppedLate = 0L
    private var statWorstLateNs = 0L
    private var statWindowStartNs = System.nanoTime()

    /** Accounts one paced frame and periodically reports if too many are dropped or run behind the clock. */
    private fun recordFrame(lateNs: Long, presentedIt: Boolean) {
        if (presentedIt) {
            statPresented++
            if (lateNs >= BEHIND_WARN_NANOS) statPresentedLate++
        } else {
            statDroppedLate++
        }
        if (lateNs > statWorstLateNs) statWorstLateNs = lateNs
        val now = System.nanoTime()
        if (now - statWindowStartNs < HEALTH_WINDOW_NS) return
        val total = statPresented + statDroppedLate
        val offCadence = statDroppedLate + statPresentedLate
        if (total > 0 && offCadence * 100L >= total * DROP_WARN_PERCENT) {
            logger.warn(
                "$debugLabel A / V: $statDroppedLate of $total frames dropped and $statPresentedLate shown behind " +
                        "the audio clock in the last ${(now - statWindowStartNs) / 1_000_000_000L} s " +
                        "(worst lateness ${statWorstLateNs / 1_000_000} ms); decode or upload is not " +
                        "keeping the source's cadence."
            )
        } else if (MediaPlayer.DEBUG && total > 0) {
            logger.debug(
                "$debugLabel A / V health: presented=$statPresented (late=$statPresentedLate) " +
                        "droppedLate=$statDroppedLate worstLate=${statWorstLateNs / 1_000_000} ms " +
                        "queue=${queue.size}/$capacityFrames."
            )
        }
        statPresented = 0
        statPresentedLate = 0
        statDroppedLate = 0
        statWorstLateNs = 0
        statWindowStartNs = now
    }

    private fun drainAndRecycle() {
        pending.getAndSet(null)?.let { surface.recycleFrameBuffer(it.buf) }
        while (true) {
            val tf = queue.poll() ?: break
            surface.recycleFrameBuffer(tf.buf)
        }
    }

    companion object {
        private const val POLL_MS = 50L
        private const val JOIN_MS = 500L

        private const val PARK_POLL_MS = 2L

        /** Reporting window for the A / V health counters. */
        private const val HEALTH_WINDOW_NS = 10_000_000_000L

        /** Share of frames off the source's cadence inside one window that is worth a warning. */
        private const val DROP_WARN_PERCENT = 20L

        /** Lateness at which a presented frame counts as behind the clock rather than on it. */
        private const val BEHIND_WARN_NANOS = 80_000_000L

        /** Default prebuffer cushion. Smooths cold start / seek / quality-switch; kept under the
         *  TimelineFollower's 1s catch-up tolerance so the added startup latency never reads as drift. */
        private const val DEFAULT_PREBUFFER_MS = 400L

        /** Floor for the prefill deadline, so a slow source still starts within a bounded time. */
        private const val DEFAULT_PRIME_DEADLINE_MS = 800L

        /** Prebuffer depth in ms. On by default; set `-Ddreamdisplays.playback.prebufferMs=0` to disable. */
        val prebufferMs: Long =
            System.getProperty("dreamdisplays.playback.prebufferMs")?.toLongOrNull()?.coerceIn(0, 5_000)
                ?: DEFAULT_PREBUFFER_MS

        /**
         * Longest the prefill may hold playout back, `-Ddreamdisplays.playback.prebufferPrimeMs` to override. Only
         * reached when the source delivers slower than real time (cold network start), where waiting out the whole
         * cushion would delay the start more than it smooths it.
         */
        private val PRIME_DEADLINE_NANOS: Long =
            (System.getProperty("dreamdisplays.playback.prebufferPrimeMs")?.toLongOrNull()?.coerceIn(0, 5_000)
                ?: maxOf(DEFAULT_PRIME_DEADLINE_MS, prebufferMs)) * 1_000_000L

        /** True when the prebuffer is enabled and the pipes should route frames through it. */
        val enabled: Boolean get() = prebufferMs > 0

        /**
         * Builds a started prebuffer sized for [frameNs] (one frame's duration), or null when disabled.
         * Capacity is the prefill plus headroom so the producer keeps a little slack above the prefill mark.
         */
        fun createIfEnabled(
            surface: FrameSurface, frameNs: Long,
            getAudioClock: () -> Long, onFirstFrame: () -> Unit,
            terminated: AtomicBoolean, stopFlag: AtomicBoolean, debugLabel: String,
            presentPreview: Boolean = true, tolerateLateness: Boolean = true,
            parkFlag: AtomicBoolean? = null,
        ): FramePrebuffer? {
            if (!enabled || frameNs <= 0) return null
            val prefill = ((prebufferMs * 1_000_000L) / frameNs).toInt().coerceIn(2, 240)
            // A little slack above the prefill mark so the producer can run slightly ahead of playout.
            // Memory cost is capacity * frameSize, so keep it tight — raw frames are large at high-res.
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
                tolerateLateness,
                parkFlag,
            )
                .also { it.start() }
        }
    }
}
