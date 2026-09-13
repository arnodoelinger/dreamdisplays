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
        /** No epoch seen yet ([AudioSink] epochs start at 1). */
        const val NO_EPOCH = 0

        /**
         * How long the line clock may sit at the same position before the master clock takes over on
         * wall time. Comfortably above the PCM line's own capacity (~0.4 s), so an ordinary underrun
         * rides through untouched and only a genuinely dead clock trips it.
         */
        const val STALL_TAKEOVER_NANOS = 750_000_000L

        /**
         * How far behind the current wall position an exact PTS anchor may pull the pacing clock. A
         * hold this long clears through normal pacing waits; anything more would trip the
         * give-up-and-drop path in [FramePacing] on every queued frame instead.
         */
        const val MAX_BACKWARD_ANCHOR_NANOS = 800_000_000L

        /** Maximum plausible bias from a stream PTS anchor; anything more is probably a bug. */
        const val MAX_PLAUSIBLE_EXACT_BIAS_NANOS = 30_000_000_000L

        /**
         * Smallest recovery gap worth resynchronizing the audio for. Below this the clock simply
         * plateaus for a few frames while the line catches up, which nobody can see; above it the
         * sound would stay behind the picture for the rest of the session.
         */
        const val MIN_RESYNC_NANOS = 150_000_000L

        /** How often a still-behind audio line may be asked to skip again while a takeover runs. */
        const val RESYNC_REQUEST_INTERVAL_NANOS = 500_000_000L
    }

    private val lock = Any()

    /** [AudioSink.ClockSample.epoch] the current anchor was computed for. */
    private var epoch = NO_EPOCH

    /** Offset added to the raw line clock to put it on the video timeline; 0 for a known origin. */
    private var bias = 0L

    /** Last raw line position seen. */
    private var lastRaw = 0L

    /** True while the line clock is presumed dead and wall time is driving playback. */
    private var takeover = false
    private var takeoverAnchorWall = 0L
    private var takeoverAnchorOut = 0L

    /** Highest value returned inside the current continuous run; guards against a backwards clock. */
    private var lastOut = Long.MIN_VALUE

    /**
     * When [lastOut] last actually moved. This — not the raw line position — is what a stall means to
     * everything downstream: a clock held flat by the monotonic guard while the line crawls back up
     * to it freezes the picture exactly as thoroughly as a dead line does.
     */
    private var lastOutAdvanceNanos = 0L

    /** When the audio side was last asked to skip ahead, so a running takeover doesn't spam it. */
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
            if (sample.epoch != epoch) beginEpoch(sample, wallNanos, exactBias, now)

            if (sample.nanos != lastRaw) {
                lastRaw = sample.nanos
                if (takeover) reconcileTakeover(sample, now)
            }

            val candidate =
                if (takeover) takeoverAnchorOut + (now - takeoverAnchorWall)
                else sample.nanos + bias

            when {
                candidate > lastOut -> {
                    lastOut = candidate
                    lastOutAdvanceNanos = now
                }
                // A parked session is meant to stand still; that is not a stall to recover from
                suspended -> lastOutAdvanceNanos = now

                !takeover && wallNanos >= 0L && now - lastOutAdvanceNanos >= STALL_TAKEOVER_NANOS ->
                    beginTakeover(sample, now)
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

    /** Computes the anchor for a freshly observed audio session. Caller holds [lock]. */
    private fun beginEpoch(
        sample: AudioSink.ClockSample,
        wallNanos: Long,
        exactBias: () -> Long?,
        now: Long,
    ) {
        epoch = sample.epoch
        takeover = false
        lastRaw = sample.nanos
        lastOutAdvanceNanos = now
        lastResyncRequestNanos = Long.MIN_VALUE / 2
        lastOut = Long.MIN_VALUE

        if (sample.originKnown) {
            bias = 0L
            return
        }
        val exact = exactBias()?.takeIf {
            it in -799999999..<MAX_PLAUSIBLE_EXACT_BIAS_NANOS
        }
        if (exact != null) {
            // Live video can't rewind, so an anchor that would park the clock further behind than
            // pacing can absorb is floored instead of applied literally.
            val floor = (if (wallNanos >= 0L) wallNanos else sample.nanos) - sample.nanos - MAX_BACKWARD_ANCHOR_NANOS
            bias = maxOf(exact, floor)
            logger.debug(
                "$debugLabel A/V anchored by stream PTS: audio joined ${exact / 1_000_000} ms " +
                        "${if (exact >= 0) "ahead of" else "behind"} the video join" +
                        if (bias != exact) " (floored by ${(bias - exact) / 1_000_000} ms: live video can't rewind)."
                        else "."
            )
            return
        }
        bias = if (wallNanos >= 0L) wallNanos - sample.nanos else 0L
        logger.debug(
            "$debugLabel Audio session joined at an unknown content offset; " +
                    "wall-anchored by ${bias / 1_000_000} ms."
        )
    }
}
