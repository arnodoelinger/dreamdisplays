package com.dreamdisplays.media.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Zero-added-latency peak limiter (fast attack, slower release feedback envelope, no lookahead buffer). A true lookahead
 * limiter would add delay we can't afford in a live pipeline.
 */
class Limiter(sampleRate: Float, private val ceiling: Float = 0.891f /* -1 dBFS */) {
    private val peakDecayCoeff = exp(-1f / (0.050f * sampleRate)) // 50ms peak-follower release
    private val attackCoeff = exp(-1f / (0.001f * sampleRate)) // ~1ms gain attack
    private val releaseCoeff = exp(-1f / (0.080f * sampleRate)) // ~80ms gain release
    private var peakEnv = 0f
    private var gain = 1f
    var lastL = 0f
    var lastR = 0f

    /** Applies linked-stereo gain reduction to one L / R sample pair, storing the result in [lastL] and [lastR]. */
    fun process(l: Float, r: Float) {
        val instant = max(abs(l), abs(r))
        peakEnv = max(instant, peakEnv * peakDecayCoeff)
        val targetGain = if (peakEnv > 1e-9f) minOf(1f, ceiling / peakEnv) else 1f
        gain = if (targetGain < gain) {
            targetGain + (gain - targetGain) * attackCoeff
        } else {
            targetGain + (gain - targetGain) * releaseCoeff
        }
        lastL = (l * gain).coerceIn(-ceiling, ceiling)
        lastR = (r * gain).coerceIn(-ceiling, ceiling)
    }

    /** Resets the peak envelope and gain-reduction state (call on session reset). */
    fun reset() {
        peakEnv = 0f
        gain = 1f
    }
}
