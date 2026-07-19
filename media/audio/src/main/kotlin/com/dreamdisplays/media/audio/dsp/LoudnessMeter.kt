package com.dreamdisplays.media.audio.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * Real-time loudness estimator and slow makeup-gain controller, in the spirit of ITU-R BS.1770 /
 * EBU R128. This is a single-stage running estimate (K-weighting pre-filter + one exponential
 * integrator), not the full two-stage gated algorithm from the spec: that needs multi-second
 * lookback windows that don't fit a live 50 ms audio block, and a slow-adapting approximation is
 * all a makeup-gain controller needs anyway.
 */
class LoudnessMeter(private val sampleRate: Float) {
    private val shelf = Biquad().apply { configure(Biquad.Type.HIGH_SHELF, sampleRate, 1500f, 0.7f, 4f) }
    private val highPass = Biquad().apply { configure(Biquad.Type.HIGH_PASS, sampleRate, 60f, 0.5f) }

    /** Running mean-square of the K-weighted signal; ~3s integration time constant. */
    private var meanSquare = 1e-9f
    private val integrationSeconds = 3f

    /** Smoothed makeup gain applied on top of the source's own mix gain, in dB. */
    private val gainDbSmoother = ParamSmoother(0.5f)

    /** Filters one K-weighted sample into the running loudness estimate; does not alter the signal. */
    fun observe(sampleL: Float, sampleR: Float, dtPerSample: Float) {
        val mono = (sampleL + sampleR) * 0.5f
        val weighted = highPass.process(shelf.process(mono))
        val alpha = 1f - exp(-dtPerSample / integrationSeconds)
        meanSquare += (weighted * weighted - meanSquare) * alpha
    }

    /** Current integrated loudness estimate in LUFS. */
    fun loudnessLufs(): Float = -0.691f + 10f * log10(max(meanSquare, 1e-9f))

    /**
     * Computes the makeup gain (linear multiplier) needed to move the current estimate toward
     * [targetLufs], clamped to [-maxCutDb, +maxBoostDb] and slew-limited to [maxSlewDbPerSecond] over
     * [dtSeconds]. The cut side is capped much tighter than the boost side: this multiplies into the
     * same sample as the spatial chain's own distance / directivity / occlusion attenuation
     * ([AudioRenderChain]), which is already quiet by design when the listener is far, off-axis, or
     * behind an obstruction — stacking a full [maxCutDb] loudness cut on top of that compounds into
     * near silence for ordinarily-loud source content. Quiet content still gets the full boost.
     */
    fun makeupGain(targetLufs: Float, maxBoostDb: Float, maxCutDb: Float, maxSlewDbPerSecond: Float, dtSeconds: Float): Float {
        val desiredDb = (targetLufs - loudnessLufs()).coerceIn(-maxCutDb, maxBoostDb)
        val maxStep = maxSlewDbPerSecond * dtSeconds
        val current = gainDbSmoother.value
        val next = (desiredDb - current).coerceIn(-maxStep, maxStep) + current
        gainDbSmoother.snap(next)
        return dbToLinear(next)
    }

    /** Resets both the K-weighting filter state and the integrated estimate (call on session reset). */
    fun reset() {
        shelf.reset(); highPass.reset()
        meanSquare = 1e-9f
        gainDbSmoother.snap(0f)
    }

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
}
