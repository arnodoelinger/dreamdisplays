package com.dreamdisplays.player.pipeline

/** Tracks playback position as seek offset + elapsed wall time. It's thread-safe for reads. */
internal class PlaybackClock {

    companion object {
        private const val NOT_STARTED = Long.MIN_VALUE
    }

    @Volatile var seekOffsetNanos = 0L
    @Volatile private var startWallNanos = NOT_STARTED

    val isRunning: Boolean get() = startWallNanos != NOT_STARTED

    /**
     * Returns the current playback position in nanos, based on the seek offset and elapsed wall time.
     */
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

    /** Resets the clock to a new seek position (pauses the wall clock). */
    fun reset(offsetNanos: Long) {
        seekOffsetNanos = offsetNanos
        startWallNanos = NOT_STARTED
    }
}
