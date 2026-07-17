package com.dreamdisplays.media.audio.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Zero-added-latency peak limiter (fast attack, slower release feedback envelope, no lookahead
 * buffer). A true lookahead limiter delays the signal by its lookahead window, which would shift
 * `AudioSink`'s line-frame-position A / V clock and needs `preludeFrames`-style compensation; this
 * stays sync-safe at the cost of a few extra ms of attack time on the hardest transients.
 */
class Limiter(sampleRate: Float, private val ceiling: Float = 0.891f /* -1 dBFS */) {
    private val peakDecayCoeff = exp(-1f / (0.050f * sampleRate)) // 50ms peak-follower release
    private val attackCoeff = exp(-1f / (0.001f * sampleRate)) // ~1ms gain attack
    private val releaseCoeff = exp(-1f / (0.080f * sampleRate)) // ~80ms gain release
    private var peakEnv = 0f
    private var gain = 1f
    var lastL = 0f
    var lastR = 0f

    /**
     * Applies linked-stereo gain reduction to one L / R sample pair, storing result in [lastL] and [lastR].
     * The raw instantaneous sample is fed through a peak-hold-with-decay envelope first (decaying slower
     * than one audio cycle, or it would ripple and let the gain relax back up mid-cycle); the smoothed
     * gain is then a defense-in-depth measure, not the sole guarantee, so the result is also hard
     * clamped to the ceiling.
     */
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
