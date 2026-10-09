package com.dreamdisplays.media.audio.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Real-time loudness estimator and slow makeup-gain controller, in the spirit of ITU-R BS.1770 / EBU R128.
 * Not a certified meter, just enough to normalize sources.
 *
 * Silence and passages far below the running level are gated out of the estimate, so a quiet stretch
 * or a pause holds the gain where it was instead of winding it up for the next loud moment.
 */
class LoudnessMeter(private val sampleRate: Float) {
    private companion object {
        const val INTEGRATION_SECONDS = 8f
        const val MOMENTARY_SECONDS = 0.4f
        const val GATE_MEAN_SQUARE = 1.2e-6f
        const val RELATIVE_GATE = 0.0316f
        const val MIN_HEARD_SECONDS = 0.4f
        const val SETTLE_SECONDS = 3f
        const val SETTLE_SLEW_DB_PER_SECOND = 10f
    }

    private val shelf = Biquad().apply { configure(Biquad.Type.HIGH_SHELF, sampleRate, 1500f, 0.7f, 4f) }
    private val highPass = Biquad().apply { configure(Biquad.Type.HIGH_PASS, sampleRate, 60f, 0.5f) }
    private val momentaryAlpha = 1f - exp(-1f / (MOMENTARY_SECONDS * sampleRate))
    private val integrationAlpha = 1f - exp(-1f / (INTEGRATION_SECONDS * sampleRate))
    private var momentary = 1e-9f
    private var meanSquare = 1e-9f
    private var heardSeconds = 0f
    private val gainDbSmoother = ParamSmoother(0.5f)

    /** Filters one K-weighted sample into the running loudness estimate; does not alter the signal. */
    fun observe(sampleL: Float, sampleR: Float, dtPerSample: Float) {
        val mono = (sampleL + sampleR) * 0.5f
        val weighted = highPass.process(shelf.process(mono))
        val square = weighted * weighted
        momentary += (square - momentary) * momentaryAlpha
        if (momentary < GATE_MEAN_SQUARE || momentary < meanSquare * RELATIVE_GATE) return

        heardSeconds += dtPerSample
        val alpha = if (heardSeconds < INTEGRATION_SECONDS) dtPerSample / heardSeconds else integrationAlpha
        meanSquare += (square - meanSquare) * alpha
    }

    /** Current integrated loudness estimate in LUFS. */
    fun loudnessLufs(): Float = -0.691f + 10f * log10(max(meanSquare, 1e-9f))

    /**
     * Computes the makeup gain (linear multiplier) needed to move the current estimate toward [targetLufs],
     * clamping both the correction range and the per-second slew rate.
     */
    fun makeupGain(
        targetLufs: Float,
        maxBoostDb: Float,
        maxCutDb: Float,
        maxSlewDbPerSecond: Float,
        dtSeconds: Float
    ): Float {
        val current = gainDbSmoother.value
        if (heardSeconds < MIN_HEARD_SECONDS) return dbToLinear(current)

        val desiredDb = (targetLufs - loudnessLufs()).coerceIn(-maxCutDb, maxBoostDb)
        val slew = if (heardSeconds < SETTLE_SECONDS) max(maxSlewDbPerSecond, SETTLE_SLEW_DB_PER_SECOND) else maxSlewDbPerSecond
        val maxStep = slew * dtSeconds
        val next = (desiredDb - current).coerceIn(-maxStep, maxStep) + current
        gainDbSmoother.snap(next)
        return dbToLinear(next)
    }

    /**
     * Starts the estimate over but keeps the gain (call on session reset). After a seek the same
     * video measures the same and the gain stays put; a new video is measured afresh and the gain
     * settles on it quickly, without first jumping back to unity.
     */
    fun restart() {
        shelf.reset(); highPass.reset()
        momentary = 1e-9f
        meanSquare = 1e-9f
        heardSeconds = 0f
    }

    /** Resets the K-weighting filter state, the integrated estimate and the gain. */
    fun reset() {
        restart()
        gainDbSmoother.snap(0f)
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
}
