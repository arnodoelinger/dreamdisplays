package com.dreamdisplays.media.audio.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class LoudnessMeterTest {
    @Test
    fun `makeup gain boosts a quiet signal toward the target and respects the clamp`() {
        val sampleRate = 44100f
        val meter = LoudnessMeter(sampleRate)
        val dtSample = 1f / sampleRate
        val blockFrames = 2205 // ~50ms
        var gain = 1f

        // A very quiet tone should need boosting, but never beyond the configured clamp
        repeat(200) { block ->
            for (i in 0 until blockFrames) {
                val idx = block * blockFrames + i
                val x = 0.01f * sin(2.0 * PI * 440.0 * idx / sampleRate).toFloat()
                meter.observe(x, x, dtSample)
            }
            gain = meter.makeupGain(-16f, 12f, 0.5f, blockFrames / sampleRate)
        }

        assertTrue(gain > 1f, "Expected a quiet signal to receive positive makeup gain, got $gain.")
        val maxLinear = Math.pow(10.0, 12.0 / 20.0).toFloat()
        assertTrue(gain <= maxLinear + 1e-3f, "Makeup gain $gain exceeded the +12dB clamp ($maxLinear).")
    }

    @Test
    fun `reset clears the integrated estimate back to silence`() {
        val meter = LoudnessMeter(44100f)
        repeat(1000) { meter.observe(0.5f, 0.5f, 1f / 44100f) }
        meter.reset()
        assertTrue(
            meter.loudnessLufs() < -80f,
            "Expected near-silence right after reset, got ${meter.loudnessLufs()} LUFS."
        )
    }
}
