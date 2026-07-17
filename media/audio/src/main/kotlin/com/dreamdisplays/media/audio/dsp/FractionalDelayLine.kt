package com.dreamdisplays.media.audio.dsp

import kotlin.math.floor

/** Linear-interpolated delay line for sub-sample ITD delays. [capacity] must exceed the largest delay ever requested. */
class FractionalDelayLine(private val capacity: Int = 128) {
    private val buf = FloatArray(capacity)
    private var writeIdx = 0

    /** Pushes the newest sample. */
    fun push(x: Float) {
        buf[writeIdx] = x
        writeIdx = (writeIdx + 1) % capacity
    }

    /** Returns the sample [delaySamples] behind the most recently pushed one (0 = most recent), interpolated. */
    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(0f, (capacity - 2).toFloat())
        val pos = (writeIdx - 1 - d)
        var wrapped = pos % capacity
        if (wrapped < 0f) wrapped += capacity
        val i0 = floor(wrapped).toInt()
        val frac = wrapped - i0
        val i1 = (i0 + 1) % capacity
        return buf[i0] * (1f - frac) + buf[i1] * frac
    }

    /** Clears the buffer (call on session reset so a stale tail never bleeds into fresh audio). */
    fun reset() {
        buf.fill(0f)
        writeIdx = 0
    }
}
