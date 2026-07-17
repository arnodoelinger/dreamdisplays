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
            limiter.process(x, x)
            if (i > settleSamples) {
                assertTrue(abs(limiter.lastL) <= 0.891f + 1e-3f, "Left exceeded ceiling: ${limiter.lastL} at sample $i.")
                assertTrue(abs(limiter.lastR) <= 0.891f + 1e-3f, "Right exceeded ceiling: ${limiter.lastR} at sample $i.")
            }
        }
    }

    @Test
    fun `passes a quiet signal through essentially unchanged`() {
        val limiter = Limiter(44100f)
        limiter.process(0.1f, -0.1f)
        assertTrue(abs(limiter.lastL - 0.1f) < 1e-3f)
        assertTrue(abs(limiter.lastR - (-0.1f)) < 1e-3f)
    }
}
