package com.dreamdisplays.media.audio.dsp

import kotlin.math.exp

/**
 * One-pole exponential smoother for block-rate control parameters (gains, angles, delay targets),
 * so a source's geometry update cadence never produces an audible zipper step in the DSP chain.
 */
class ParamSmoother(private val timeConstantSeconds: Float, initial: Float = 0f) {
    /** Current smoothed value. */
    var value: Float = initial
        private set

    /** Advances the smoother toward [target] over [dtSeconds] and returns the new [value]. */
    fun next(target: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f) return value
        val alpha = 1f - exp(-dtSeconds / timeConstantSeconds)
        value += (target - value) * alpha
        return value
    }

    /** Jumps directly to [v] with no ramp, e.g. on session reset. */
    fun snap(v: Float) {
        value = v
    }
}
