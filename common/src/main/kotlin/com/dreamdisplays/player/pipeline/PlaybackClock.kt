package com.dreamdisplays.player.pipeline

/** Tracks playback position as seek offset and elapsed wall time. It's thread-safe for reads. */
internal class PlaybackClock {
    companion object {
        private const val NOT_STARTED = Long.MIN_VALUE
    }

    @Volatile var seekOffsetNanos = 0L
    @Volatile private var startWallNanos = NOT_STARTED

    val isRunning: Boolean get() = startWallNanos != NOT_STARTED

    /** Returns the current playback position in nanos, based on the seek offset and elapsed wall time. */
    fun currentTime(): Long {
        val start = startWallNanos
        return if (start == NOT_STARTED) seekOffsetNanos
        else seekOffsetNanos + (System.nanoTime() - start)
    }

    /**
     * Converts an audio line frame position into absolute playback nanos.
     * Returns -1 when [framePos] is negative (no audio line open yet).
     */
    fun audioClockNanos(framePos: Long, sampleRate: Int): Long {
        if (framePos < 0) return -1L
        return seekOffsetNanos + (framePos * 1_000_000_000L / sampleRate)
    }

    /** Called once by the video thread when the first frame arrives. */
    fun markFirstFrame() {
        if (startWallNanos == NOT_STARTED) startWallNanos = System.nanoTime()
    }

    /**
     * Shifts the wall-clock origin forward by [nanos] so a paused (parked) interval is excluded from
     * elapsed time — used on un-park so [currentTime] resumes from where it froze instead of jumping
     * ahead by the time the display sat dormant. No-op when the clock isn't running.
     */
    fun addPausedDuration(nanos: Long) {
        if (startWallNanos != NOT_STARTED && nanos > 0) startWallNanos += nanos
    }

    /** Resets the clock to a new seek position (pauses the wall clock). */
    fun reset(offsetNanos: Long) {
        seekOffsetNanos = offsetNanos
        startWallNanos = NOT_STARTED
    }

    /**
     * Atomically re-anchors a *running* clock to [offsetNanos] as of now — used at the replay -> live
     * handoff so the wall clock and the live audio (offset at the same position) agree without a gap.
     * Unlike [reset] the clock stays running, so pacing never momentarily reads "not started".
     */
    fun rebaseTo(offsetNanos: Long) {
        seekOffsetNanos = offsetNanos
        startWallNanos = System.nanoTime()
    }
}
