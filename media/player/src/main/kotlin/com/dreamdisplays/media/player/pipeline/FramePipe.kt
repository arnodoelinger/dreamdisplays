package com.dreamdisplays.media.player.pipeline

import com.dreamdisplays.api.media.FramePixelFormat
import com.dreamdisplays.api.media.player.GpuTextureRef
import com.dreamdisplays.media.player.pipeline.FramePacing.MAX_PACING_WAIT_NS
import com.dreamdisplays.media.player.pipeline.FramePacing.STALE_TIMELINE_DIFF_NS
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

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
    fun updateFramePlanar(y: GpuTextureRef, u: GpuTextureRef, v: GpuTextureRef, actualW: Int, actualH: Int): Boolean = false

    /** Discards the current ready frame. Call when stopping or seeking. */
    fun clear()

    /** Drops non-essential raw frame buffers while a session is fully warm-parked. */
    fun trimForPark() = clear()

    /** Releases GPU resources. Must be called from the render thread on permanent shutdown. */
    fun cleanup()
}

/**
 * Caches the most recent frame handed to a pipe's raw-frame sink (popout / menu preview) so a
 * sink that attaches while playback is paused or parked - and so won't see a new decoded frame
 * for a while, possibly ever if playback stays paused - gets an immediate picture instead of
 * waiting indefinitely for the next one.
 */
internal class LastFrameCache {
    @Volatile
    private var buffer: ByteBuffer? = null
    private var width = 0
    private var height = 0
    private var format = FramePixelFormat.RGB24

    /** Reader-thread only: stores a copy of the first [size] bytes of [src] (read from position 0). */
    fun store(src: ByteBuffer, w: Int, h: Int, size: Int, fmt: FramePixelFormat) {
        var dst = buffer
        if (dst == null || dst.capacity() < size) {
            dst = ByteBuffer.allocateDirect(size)
        }
        dst.clear()
        val view = src.duplicate()
        view.position(0).limit(size)
        dst.put(view)
        dst.flip()
        width = w
        height = h
        format = fmt
        buffer = dst
    }

    /** Replays the cached frame into [sink], if one has been stored yet. Safe to call from any thread. */
    fun replay(sink: (ByteBuffer, Int, Int, FramePixelFormat) -> Unit) {
        val buf = buffer ?: return
        sink(buf.duplicate(), width, height, format)
    }
}

/** A/V pacing shared by both frame pipes: sleep until a frame is due, drop when too late. */
internal object FramePacing {
    /** Threshold under which we use busy-wait (spin) instead of sleep for precise timing. */
    private const val SPIN_THRESHOLD_NS = 2L * 1_000_000L

    /** Drop a frame when it's more than 80 ms behind the audio clock. */
    private const val DROP_THRESHOLD_NS = 80_000_000L

    /** Cap on a single park slice so an [abort] request is honored promptly mid-wait. */
    private const val ABORT_POLL_NS = 50L * 1_000_000L

    /**
     * A pacing wait this long is anomalous (stalled clock, late audio start after a seek, or a
     * frame from a stale timeline) — logged (rate-limited) so it's never silent, but the wait
     * itself continues: the clock usually recovers and the frame becomes due.
     */
    private const val SUSPICIOUS_WAIT_NS = 2_000_000_000L

    /**
     * Any diff larger than this, for a caller that has no independent way to tell a stale-timeline
     * frame from real ongoing lag ([dropStaleTimeline] = true), is treated as the frame belonging to
     * a different timeline than the current audio clock (typical after back-to-back seeks: video
     * comes back on the new timeline before the previous seek's audio line has been torn down and
     * replaced, so the queued cushion carries pre-seek PTS while the new line drives the clock).
     * Waiting for the clock to catch up is pointless in that case because it never will on the
     * frame's timeline; drop the frame immediately. The bound is tight: normal steady-state diff at
     * the head of the queue is one frame period (~33 ms), post-seek/cold-start transient tops out
     * near the prebuffer cushion (400 ms default) while the audio line is warming up — but a genuine
     * same-timeline stall (e.g. a game-thread hitch delaying the audio line) can just as easily push
     * the real audio clock a second or more behind, which this heuristic can't distinguish from a
     * dead timeline; [FramePrebuffer] passes `dropStaleTimeline = false` because it already filters
     * true stale frames by their own generation tag before calling [pace], so any diff it hands in
     * is guaranteed same-timeline and should be waited out, not dropped outright.
     */
    private const val STALE_TIMELINE_DIFF_NS = 1_000_000_000L

    /**
     * Give up on a frame after actually waiting this long: either it belongs to a dead timeline
     * (survived a seek's clock reset) or the clock is stuck — both are watchdog territory, and
     * holding the consumer any longer would freeze playout behind one frame. Tightened from the
     * original 8 s because the queue backs up quickly under a stuck head — one blocked head means
     * every subsequent frame waits behind it, so at 30 fps the producer is stalled within 400 ms.
     */
    private const val MAX_PACING_WAIT_NS = 1_500_000_000L

    /**
     * If the wait budget is exhausted but the frame is still only a little ahead of the frozen
     * clock, present it anyway instead of dropping.
     */
    private const val GIVE_UP_PRESENT_THRESHOLD_NS = DROP_THRESHOLD_NS

    /** Rate limiter for the suspicious-wait warn: at most one line per second across all frames. */
    private val lastWarnNanos = AtomicLong(0)

    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/FramePacing")

    /** Single-sample clock entry point. */
    fun pace(videoPts: Long, audioClock: Long): Boolean = pace(videoPts, { audioClock })

    /**
     * Paces the reader thread against the audio clock: parks/spins until [videoPts] is due, then
     * re-samples the clock so an overslept frame is dropped instead of being presented late.
     * [audioClock] may return -1 when no audio line is open yet, and is re-sampled on every park
     * slice, so a clock reset (seek) or a late-starting clock takes effect immediately instead of
     * being baked into a one-shot park. When [abort] turns true mid-wait (seek flush, teardown)
     * the wait ends early and the frame is reported as a drop; a frame still not due after
     * [MAX_PACING_WAIT_NS] of real waiting is dropped too (stale timeline / dead clock).
     *
     * @param dropStaleTimeline Whether a diff past [STALE_TIMELINE_DIFF_NS] is assumed to mean the
     * frame is from a dead timeline and gets dropped without waiting. Only safe when the caller has
     * no cheaper/exact way to tell that apart from real lag; [FramePrebuffer] already does (its own
     * generation tag), so it passes false and lets a same-timeline stall resolve through the normal
     * wait / [MAX_PACING_WAIT_NS] give-up path instead of being misread as a stale frame forever.
     */
    fun pace(videoPts: Long, audioClock: () -> Long, abort: () -> Boolean = { false }, dropStaleTimeline: Boolean = true): Boolean {
        val started = System.nanoTime()
        while (true) {
            if (abort()) return true
            val clock = audioClock()
            val diff = videoPts - if (clock >= 0) clock else videoPts
            if (diff <= 0) break
            if (dropStaleTimeline && clock >= 0 && diff >= STALE_TIMELINE_DIFF_NS) {
                val now = System.nanoTime()
                val last = lastWarnNanos.get()
                if (now - last >= 1_000_000_000L && lastWarnNanos.compareAndSet(last, now)) {
                    logger.debug(
                        "Dropping stale frame (videoPts=${videoPts / 1_000_000} ms, " +
                                "audioClock=${clock / 1_000_000} ms, diff=${diff / 1_000_000} ms); " +
                                "clock belongs to a different timeline than the frame."
                    )
                }
                return true
            }
            if (System.nanoTime() - started >= MAX_PACING_WAIT_NS) {
                if (diff <= GIVE_UP_PRESENT_THRESHOLD_NS) {
                    logger.warn(
                        "Pacing wait hit ${MAX_PACING_WAIT_NS / 1_000_000} ms with nearly-due frame " +
                                "(videoPts=${videoPts / 1_000_000} ms, audioClock=${clock / 1_000_000} ms); " +
                                "clock appears frozen, presenting instead of dropping."
                    )
                    break
                }
                logger.warn(
                    "Gave up pacing after ${MAX_PACING_WAIT_NS / 1_000_000} ms " +
                            "(videoPts=${videoPts / 1_000_000} ms, audioClock=${clock / 1_000_000} ms); " +
                            "dropping the frame."
                )
                return true
            }
            if (diff >= SUSPICIOUS_WAIT_NS) {
                val now = System.nanoTime()
                val last = lastWarnNanos.get()
                if (now - last >= 1_000_000_000L && lastWarnNanos.compareAndSet(last, now)) {
                    logger.warn(
                        "Pacing wait of ${diff / 1_000_000} ms (videoPts=${videoPts / 1_000_000} ms, " +
                                "audioClock=${clock / 1_000_000} ms); clock stalled or far behind; waiting."
                    )
                }
            }
            if (diff > SPIN_THRESHOLD_NS) {
                LockSupport.parkNanos(minOf(diff - SPIN_THRESHOLD_NS, ABORT_POLL_NS))
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt()
                    break
                }
            } else {
                Thread.onSpinWait()
            }
        }
        val latestClock = audioClock()
        val latestDiff = videoPts - if (latestClock >= 0) latestClock else videoPts
        return latestDiff < -DROP_THRESHOLD_NS
    }
}
