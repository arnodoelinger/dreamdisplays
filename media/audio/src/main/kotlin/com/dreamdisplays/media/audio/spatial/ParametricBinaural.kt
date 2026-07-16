package com.dreamdisplays.media.audio.spatial

import com.dreamdisplays.media.audio.dsp.Biquad
import com.dreamdisplays.media.audio.dsp.FractionalDelayLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders one mono emitter into a stereo binaural pair using a parametric ITD (Woodworth's formula)
 * + ILD (broadband gain plus a contralateral low-pass for the head-shadow effect) model. A separate
 * instance is needed per emitter so each keeps its own delay line / filter state.
 */
class ParametricBinaural(private val sampleRate: Float) {
    private companion object {
        const val HEAD_RADIUS_M = 0.0875
        const val SPEED_OF_SOUND = 343.0
        const val MAX_ILD_ATTEN = 0.35f
        const val OPEN_CUTOFF_HZ = 9000f
        const val SHADOWED_CUTOFF_HZ = 2200f
        val HALF_PI = (PI / 2.0)
    }

    private val delayLine = FractionalDelayLine(64)
    private val shadowFilter = Biquad().apply { configure(Biquad.Type.LOW_PASS, sampleRate, OPEN_CUTOFF_HZ, 0.7f) }

    private var itdSamples = 0f
    private var farIsRight = true
    private var farGain = 1f

    /**
     * Recomputes ITD / ILD / head-shadow cutoff from [azimuthRad] (0 = ahead, positive = to the
     * listener's right). Call once per block; [renderSample] reuses these cached values per-sample.
     */
    fun updateParams(azimuthRad: Double) {
        val clamped = azimuthRad.coerceIn(-HALF_PI, HALF_PI)
        val itdSeconds = (HEAD_RADIUS_M / SPEED_OF_SOUND) * (clamped + sin(clamped))
        itdSamples = (abs(itdSeconds) * sampleRate).toFloat()
        farIsRight = clamped < 0.0
        val mag = abs(sin(clamped))
        farGain = (1.0 - MAX_ILD_ATTEN * mag).toFloat()
        val cutoff = (OPEN_CUTOFF_HZ - (OPEN_CUTOFF_HZ - SHADOWED_CUTOFF_HZ) * mag).toFloat()
        shadowFilter.configure(Biquad.Type.LOW_PASS, sampleRate, cutoff, 0.7f)
    }

    /** Renders one mono [sample] into a binaural `[left, right]` pair using the last [updateParams] call. */
    fun renderSample(sample: Float): FloatArray {
        delayLine.push(sample)
        val near = delayLine.read(0f)
        val far = shadowFilter.process(delayLine.read(itdSamples)) * farGain
        return if (farIsRight) floatArrayOf(near, far) else floatArrayOf(far, near)
    }

    /** Clears delay-line and filter state (call on session reset). */
    fun reset() {
        delayLine.reset()
        shadowFilter.reset()
        itdSamples = 0f
        farGain = 1f
    }
}
