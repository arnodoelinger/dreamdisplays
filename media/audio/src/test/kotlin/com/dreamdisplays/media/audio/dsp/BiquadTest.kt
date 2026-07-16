package com.dreamdisplays.media.audio.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class BiquadTest {
    private val sampleRate = 44100f

    private fun rms(freqHz: Float, filter: Biquad): Float {
        filter.reset()
        var sumSq = 0.0
        val n = 4096
        for (i in 0 until n) {
            val x = sin(2.0 * PI * freqHz * i / sampleRate).toFloat()
            val y = filter.process(x)
            if (i > 512) sumSq += y * y // Skip the filter's settling transient
        }
        return sqrt(sumSq / (n - 512)).toFloat()
    }

    @Test
    fun `low-pass attenuates high frequencies more than low frequencies`() {
        val lp = Biquad().apply { configure(Biquad.Type.LOW_PASS, sampleRate, 500f, 0.707f) }
        val low = rms(100f, lp)
        val high = rms(8000f, lp)
        assertTrue(low > high, "Expected 100Hz ($low) to pass more than 8 kHz ($high) through a 500 Hz low-pass.")
        assertTrue(low > 0.7f, "A 100Hz tone should pass a 500 Hz low-pass almost untouched, got $low.")
    }

    @Test
    fun `high-pass attenuates low frequencies more than high frequencies`() {
        val hp = Biquad().apply { configure(Biquad.Type.HIGH_PASS, sampleRate, 500f, 0.707f) }
        val low = rms(50f, hp)
        val high = rms(5000f, hp)
        assertTrue(high > low, "Expected 5kHz ($high) to pass more than 50 Hz ($low) through a 500Hz high-pass.")
    }

    @Test
    fun `filter output never explodes (stable coefficients)`() {
        val shelf = Biquad().apply { configure(Biquad.Type.HIGH_SHELF, sampleRate, 1500f, 0.7f, 6f) }
        var x = 1f
        repeat(10000) {
            x = shelf.process(if (it % 2 == 0) 1f else -1f)
            assertTrue(x.isFinite() && kotlin.math.abs(x) < 100f, "Biquad output diverged: $x at sample $it.")
        }
    }
}
