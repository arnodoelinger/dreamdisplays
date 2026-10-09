package com.dreamdisplays.media.player.pipeline

import org.slf4j.LoggerFactory

/**
 * Turns [AudioSink]'s per-session line clock into the single master clock every video pipe paces
 * against.
 */
internal class AudioMasterClock(
    /** Debug label. */
    private val debugLabel: String,

    /** Monotonic time source; injectable so the stall / takeover behavior is testable without sleeping. */
    private val nowNanos: () -> Long = System::nanoTime,

    /**
     * Asks the audio side to discard this many nanos of pending sound so a line that stalled rejoins
     * the picture instead of trailing it for the rest of the session (see [nanos]).
     */
    private val requestAudioResync: (gapNanos: Long) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val NO_EPOCH = 0
        const val STALL_TAKEOVER_NANOS = 750_000_000L
        const val MAX_PLAUSIBLE_SHIFT_NANOS = 30_000_000_000L
        const val MIN_RESYNC_NANOS = 150_000_000L
        const val RESYNC_REQUEST_INTERVAL_NANOS = 500_000_000L
        const val MAX_INTERPOLATION_NANOS = 60_000_000L
        const val MAX_STEP_NANOS = 100_000_000L
    }
    private val lock = Any()
    private var epoch = NO_EPOCH
    private var bias = 0L
    private var streamAnchored = false
    private var lastRaw = 0L
    private var lastRawChangeNanos = 0L
    private var stepNanos = 0L
    private var takeover = false
    private var takeoverAnchorWall = 0L
    private var takeoverAnchorOut = 0L
    private var lastOut = Long.MIN_VALUE
    private var lastOutAdvanceNanos = 0L
    private var lastResyncRequestNanos = Long.MIN_VALUE / 2

    /**
     * Returns the master clock position in content nanos, or -1 when neither the audio line nor the wall clock
     * has a known origin. The returned value is guaranteed to be non-decreasing, even if the line clock
     * jumps backwards or stalls. The caller must hold [lock] while calling this.
     */
    fun nanos(
        sample: AudioSink.ClockSample,
        wallNanos: Long,
        suspended: Boolean,
        exactBias: () -> Long?,
    ): Long {
        if (sample.nanos < 0L) {
            // No line clock at all (starting up, between sessions, bridge prelude): run on wall time
            // and don't let the gap accrue as stall time against whatever session comes back
            synchronized(lock) { lastOutAdvanceNanos = nowNanos() }
            return wallNanos
        }
        synchronized(lock) {
            val now = nowNanos()
            if (sample.epoch != epoch) beginEpoch(sample, wallNanos, now)
            if (!sample.originKnown && !streamAnchored) alignToStream(exactBias)

            if (sample.nanos != lastRaw) {
                val interval = now - lastRawChangeNanos
                if (interval in 1..MAX_STEP_NANOS) {
                    stepNanos = if (stepNanos == 0L) interval else (stepNanos * 7 + interval) / 8
                }
                lastRaw = sample.nanos
                lastRawChangeNanos = now
                if (takeover) reconcileTakeover(sample, now)
            }

            val reach = minOf(stepNanos * 3 / 2, MAX_INTERPOLATION_NANOS)
            val sinceStep = if (suspended) 0L else (now - lastRawChangeNanos).coerceIn(0L, reach)
            val candidate =
                if (takeover) takeoverAnchorOut + (now - takeoverAnchorWall)
                else sample.nanos + bias + sinceStep

            when {
                candidate > lastOut -> {
                    lastOut = candidate
                    lastOutAdvanceNanos = now
                }
                // A parked session is meant to stand still; that is not a stall to recover from
                suspended -> lastOutAdvanceNanos = now

                !takeover && sample.originKnown && wallNanos >= 0L &&
                        now - lastOutAdvanceNanos >= STALL_TAKEOVER_NANOS -> beginTakeover(sample, now)
            }
            return lastOut
        }
    }

    /** Starts driving the clock from wall time because it has stopped moving on its own. Caller holds [lock]. */
    private fun beginTakeover(sample: AudioSink.ClockSample, now: Long) {
        val stalledForMs = (now - lastOutAdvanceNanos) / 1_000_000
        takeover = true
        takeoverAnchorWall = now
        takeoverAnchorOut = if (lastOut == Long.MIN_VALUE) sample.nanos + bias else lastOut
        lastOutAdvanceNanos = now
        logger.warn(
            "$debugLabel Audio clock stuck at ${sample.nanos / 1_000_000} ms for $stalledForMs ms; " +
                    "pacing video on wall time until it recovers."
        )
    }

    /** Leaves the wall-time takeover now that the line clock is moving again. Caller holds [lock]. */
    private fun reconcileTakeover(sample: AudioSink.ClockSample, now: Long) {
        val ramp = takeoverAnchorOut + (now - takeoverAnchorWall)
        val behind = ramp - (sample.nanos + bias)
        if (behind <= MIN_RESYNC_NANOS) {
            takeover = false
            logger.debug("$debugLabel Audio clock caught up with the picture; pacing is back on the line.")
            return
        }
        // Still behind. Handing back now would rewind the master clock by the whole gap, so wall
        // time keeps driving and the sound is asked to close the distance instead. Re-asked
        // periodically rather than once: each skip is measured against a gap that is still growing
        // while it is being applied, so one request rarely lands exactly.
        if (now - lastResyncRequestNanos < RESYNC_REQUEST_INTERVAL_NANOS) return
        lastResyncRequestNanos = now
        logger.warn(
            "$debugLabel Audio is ${behind / 1_000_000} ms behind the picture after a stall; " +
                    "skipping that much sound to re-sync."
        )
        requestAudioResync(behind)
    }

    /** Forgets all session state; call when the whole playback session is torn down. */
    fun reset() {
        synchronized(lock) {
            epoch = NO_EPOCH
            bias = 0L
            lastRaw = 0L
            lastOutAdvanceNanos = 0L
            lastResyncRequestNanos = Long.MIN_VALUE / 2
            takeover = false
            lastOut = Long.MIN_VALUE
        }
    }

    private fun beginEpoch(sample: AudioSink.ClockSample, wallNanos: Long, now: Long) {
        epoch = sample.epoch
        takeover = false
        streamAnchored = false
        lastRaw = sample.nanos
        lastRawChangeNanos = now
        stepNanos = 0L
        lastOutAdvanceNanos = now
        lastResyncRequestNanos = Long.MIN_VALUE / 2
        val live = !sample.originKnown && wallNanos >= 0L
        bias = if (live) wallNanos - sample.nanos else 0L
        lastOut = if (live) wallNanos else Long.MIN_VALUE
    }

    private fun alignToStream(exactBias: () -> Long?) {
        val exact = exactBias() ?: return
        val shift = exact - bias
        if (shift <= -MAX_PLAUSIBLE_SHIFT_NANOS || shift >= MAX_PLAUSIBLE_SHIFT_NANOS) return
        streamAnchored = true
        bias = exact
        if (shift < -MIN_RESYNC_NANOS) requestAudioResync(-shift)
        logger.debug(
            "$debugLabel A / V anchored by stream PTS: audio joined ${shift / 1_000_000} ms off the picture" +
                    if (shift < -MIN_RESYNC_NANOS) "; skipping it forward." else "."
        )
    }
}
