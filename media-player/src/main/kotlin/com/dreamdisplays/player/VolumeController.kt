package com.dreamdisplays.player

import kotlin.math.abs

/**
 * Combines the user-set volume with distance-based attenuation and pushes the effective value to
 * the audio pipeline via [applyVolume]. Extracted from `MediaPlayer` so volume policy lives in one
 * place.
 *
 * @param initialVolume starting user volume (0.0-2.0)
 * @param applyVolume sink receiving the effective volume (user volume * attenuation)
 */
internal class VolumeController(
    initialVolume: Double,
    private val applyVolume: (Double) -> Unit,
) {
    @Volatile private var userVolume = initialVolume
    @Volatile private var lastAttenuation = 1.0

    /** Sets the user-controlled volume (clamped to 0.0-2.0) and re-applies the effective value. */
    fun setUserVolume(volume: Float) {
        userVolume = volume.toDouble().coerceIn(0.0, 2.0)
        applyVolume(userVolume * lastAttenuation)
    }

    /**
     * Recomputes quadratic distance attenuation for [distance] against [maxRadius] and re-applies
     * the effective volume only when the attenuation changed materially.
     */
    fun updateAttenuation(distance: Double, maxRadius: Double) {
        val attenuation = (1.0 - minOf(1.0, distance / maxRadius)).let { it * it }
        if (abs(attenuation - lastAttenuation) > 1e-5) {
            lastAttenuation = attenuation
            applyVolume(userVolume * attenuation)
        }
    }
}
