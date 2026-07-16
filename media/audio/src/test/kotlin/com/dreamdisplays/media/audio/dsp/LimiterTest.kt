package com.dreamdisplays.media.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class LimiterTest {
    @Test
    fun `never exceeds the ceiling once the envelope has settled`() {
        val sampleRate = 44100f
        val limiter = Limiter(sampleRate, ceiling = 0.891f)
        val settleSamples = 1000 // Allow the peak-follower (5ms) and gain attack (1ms) envelopes to settle
        for (i in 0 until 20000) {
            val x = 3.0f * sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() // Way over unity
            val (l, r) = limiter.process(x, x)
            if (i > settleSamples) {
                assertTrue(abs(l) <= 0.891f + 1e-3f, "Left exceeded ceiling: $l at sample $i.")
                assertTrue(abs(r) <= 0.891f + 1e-3f, "Right exceeded ceiling: $r at sample $i.")
            }
        }
    }

    @Test
    fun `passes a quiet signal through essentially unchanged`() {
        val limiter = Limiter(44100f)
        val (l, r) = limiter.process(0.1f, -0.1f)
        assertTrue(abs(l - 0.1f) < 1e-3f)
        assertTrue(abs(r - (-0.1f)) < 1e-3f)
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
}
