package com.dreamdisplays.media.audio.dsp

import kotlin.math.roundToInt

/**
 * Compact Schröder / Freeverb-style algorithmic reverb: eight damped feedback-comb filters in parallel
 * feeding four series all-pass filters, per channel, with the right channel's delays offset by
 * [STEREO_SPREAD] so a mono send decorrelates into an enveloping stereo tail. The classic comb / all-pass
 * tunings are quoted at 44.1 kHz and rescaled to the actual sample rate at construction.
 *
 * This renders the wet signal only (no dry blend); the render chain scales it by the environment's
 * wet-gain and adds it to the already-spatialized dry mix, so the reverb acts as an aux send.
 */
class Reverb(sampleRate: Float) {
    private companion object {
        /** Comb delay lengths in samples at 44.1 kHz (Jezar's Freeverb tuning). */
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)

        /** All-pass delay lengths in samples at 44.1 kHz. */
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)

        /** Right-channel delay offset for stereo decorrelation, in samples at 44.1 kHz. */
        const val STEREO_SPREAD = 23

        const val ALLPASS_FEEDBACK = 0.5f
        const val ROOM_SCALE = 0.28f
        const val ROOM_OFFSET = 0.7f
        const val DAMP_SCALE = 0.4f
        const val GAIN = 0.015f // Input attenuation into the comb bank, keeps the tail from blowing up
    }

    private val scale = sampleRate / 44100f
    private fun scaled(n: Int): Int = (n * scale).roundToInt().coerceAtLeast(1)

    private val combsL = COMB_TUNING.map { CombFilter(scaled(it)) }
    private val combsR = COMB_TUNING.map { CombFilter(scaled(it + STEREO_SPREAD)) }
    private val allpassL = ALLPASS_TUNING.map { AllpassFilter(scaled(it), ALLPASS_FEEDBACK) }
    private val allpassR = ALLPASS_TUNING.map { AllpassFilter(scaled(it + STEREO_SPREAD), ALLPASS_FEEDBACK) }

    var lastL = 0f
    var lastR = 0f

    /**
     * Sets the tail length and tone from [roomSize] (0 = tight, 1 = cavernous) and [damping]
     * (0 = bright/hard walls, 1 = dark / soft). Call once per block; [process] reuses the cached values.
     */
    fun updateParams(roomSize: Float, damping: Float) {
        val feedback = roomSize.coerceIn(0f, 1f) * ROOM_SCALE + ROOM_OFFSET
        val damp = damping.coerceIn(0f, 1f) * DAMP_SCALE
        combsL.forEach { it.feedback = feedback; it.damp = damp }
        combsR.forEach { it.feedback = feedback; it.damp = damp }
    }

    /** Renders one mono [input] sample into a wet pair, storing result in [lastL] and [lastR]. */
    fun process(input: Float) {
        val fed = input * GAIN
        var l = 0f
        var r = 0f
        for (i in combsL.indices) {
            l += combsL[i].process(fed); r += combsR[i].process(fed)
        }
        for (ap in allpassL) l = ap.process(l)
        for (ap in allpassR) r = ap.process(r)
        lastL = l; lastR = r
    }

    /** Clears every comb / all-pass buffer so a stale tail never bleeds into a fresh session. */
    fun reset() {
        combsL.forEach { it.reset() }; combsR.forEach { it.reset() }
        allpassL.forEach { it.reset() }; allpassR.forEach { it.reset() }
    }

    /** One damped feedback-comb: a delay line whose feedback is low-pass filtered by [damp]. */
    private class CombFilter(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        var feedback = 0f
        var damp = 0f

        /** Filters one sample through the comb, updating the delay line and damping store. */
        fun process(x: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = x + store * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }

        /** Clears the delay line and damping store. */
        fun reset() {
            buf.fill(0f); store = 0f; idx = 0
        }
    }

    /** One Schröder all-pass: diffuses the comb output without coloring its magnitude response. */
    private class AllpassFilter(size: Int, private val feedback: Float) {
        private val buf = FloatArray(size)
        private var idx = 0

        /** Filters one sample through the all-pass, updating the delay line. */
        fun process(x: Float): Float {
            val bufout = buf[idx]
            val out = -x + bufout
            buf[idx] = x + bufout * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }

        /** Clears the delay line. */
        fun reset() {
            buf.fill(0f); idx = 0
        }
    }
}
