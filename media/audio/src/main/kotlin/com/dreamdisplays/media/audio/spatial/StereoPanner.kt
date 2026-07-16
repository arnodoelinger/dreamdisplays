package com.dreamdisplays.media.audio.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Constant-power stereo pan used for the "speakers" output profile (no binaural processing). */
object StereoPanner {
    private val HALF_PI = (PI / 2.0)

    /** Pans a mono [sample] to `[left, right]` given [azimuthRad] in `[-PI/2, PI/2]` (0 = center). */
    fun pan(sample: Float, azimuthRad: Double): FloatArray {
        val t = (azimuthRad.coerceIn(-HALF_PI, HALF_PI) / HALF_PI + 1.0) / 2.0 // 0 = left .. 1 = right
        val angle = t * HALF_PI
        return floatArrayOf((sample * cos(angle)).toFloat(), (sample * sin(angle)).toFloat())
    }
}
