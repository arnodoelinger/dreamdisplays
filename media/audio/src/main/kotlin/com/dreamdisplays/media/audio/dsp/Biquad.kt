package com.dreamdisplays.media.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direct Form I biquad filter, coefficients computed from the RBJ Audio EQ Cookbook formulas. */
class Biquad {
    enum class Type { LOW_PASS, HIGH_PASS, HIGH_SHELF }

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    /** Recomputes coefficients for [type] at [freqHz] / [sampleRate], with [q] and (shelf-only) [gainDb]. */
    fun configure(type: Type, sampleRate: Float, freqHz: Float, q: Float, gainDb: Float = 0f) {
        val w0 = 2.0 * PI * (freqHz / sampleRate).coerceIn(0.0001f, 0.499f)
        val cosW0 = cos(w0);
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)
        var b0d: Double
        var b1d: Double
        var b2d: Double
        var a0d: Double
        var a1d: Double
        var a2d: Double
        when (type) {
            Type.LOW_PASS -> {
                b0d = (1.0 - cosW0) / 2.0; b1d = 1.0 - cosW0; b2d = (1.0 - cosW0) / 2.0
                a0d = 1.0 + alpha; a1d = -2.0 * cosW0; a2d = 1.0 - alpha
            }

            Type.HIGH_PASS -> {
                b0d = (1.0 + cosW0) / 2.0; b1d = -(1.0 + cosW0); b2d = (1.0 + cosW0) / 2.0
                a0d = 1.0 + alpha; a1d = -2.0 * cosW0; a2d = 1.0 - alpha
            }

            Type.HIGH_SHELF -> {
                val a = Math.pow(10.0, gainDb / 40.0)
                val s = 1.0 // Shelf slope
                val alphaS = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
                val twoSqrtAAlpha = 2.0 * sqrt(a) * alphaS
                b0d = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
                b1d = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
                b2d = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
                a0d = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
                a1d = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
                a2d = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
            }
        }
        b0 = (b0d / a0d).toFloat(); b1 = (b1d / a0d).toFloat(); b2 = (b2d / a0d).toFloat()
        a1 = (a1d / a0d).toFloat(); a2 = (a2d / a0d).toFloat()
    }

    /** Filters one sample, updating internal state. */
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /** Clears filter history (call on every session reset to avoid a stale-state click). */
    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
