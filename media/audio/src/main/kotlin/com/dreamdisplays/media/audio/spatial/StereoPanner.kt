package com.dreamdisplays.media.audio.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Constant-power stereo pan used for the "speakers" output profile (no binaural processing). One
 * instance is needed per emitter (mirroring [ParametricBinaural]) since [lastL] / [lastR] are shared
 * mutable state.
 */
class StereoPanner {
    private companion object {
        val HALF_PI = (PI / 2.0)
    }

    var lastL = 0f
    var lastR = 0f

    /** Pans a mono [sample] to `[left, right]` given [azimuthRad], storing result in [lastL] and [lastR]. */
    fun pan(sample: Float, azimuthRad: Double) {
        val t = (azimuthRad.coerceIn(-HALF_PI, HALF_PI) / HALF_PI + 1.0) / 2.0 // 0 = left .. 1 = right
        val angle = t * HALF_PI
        lastL = (sample * cos(angle)).toFloat()
        lastR = (sample * sin(angle)).toFloat()
    }
}
