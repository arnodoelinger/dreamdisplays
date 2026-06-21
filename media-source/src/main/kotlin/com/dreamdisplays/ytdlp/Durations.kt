package com.dreamdisplays.ytdlp

import kotlin.math.max
import kotlin.math.round

/**
 * Shared seconds-to-nanoseconds conversion for the resolution paths ([NewPipeResolver] and
 * [YtDlpOutputParser]), with a [Long.MAX_VALUE] clamp so absurd durations never overflow.
 */
object Durations {
    /** Converts [seconds] to nanoseconds, clamped to [Long.MAX_VALUE]; returns 0 for non-positive input. */
    fun secondsToNanos(seconds: Double): Long {
        if (seconds <= 0.0) return 0L
        val nanos = seconds * 1_000_000_000.0
        if (nanos >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return max(0L, round(nanos).toLong())
    }

    /** Converts [seconds] to nanoseconds, clamped to [Long.MAX_VALUE]; returns 0 for non-positive input. */
    fun secondsToNanos(seconds: Long): Long = secondsToNanos(seconds.toDouble())
}
