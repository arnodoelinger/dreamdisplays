package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Acoustics rendering tier, from fully disabled to the highest-fidelity HRIR-based chain.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
enum class AcousticQuality {
    /** Legacy distance-gain only, no spatialization. */
    OFF,

    /** Area-source distance + directivity + constant-power stereo pan, no filtering. */
    BASIC,

    /**
     * Adds loudness normalization, a dynamic limiter, parametric binaural rendering, and the full
     * environmental chain: raytraced occlusion, distance air absorption, and algorithmic reverb.
     */
    ADVANCED,

    /** Adds true HRIR convolution and emitter-grid LOD (not yet implemented; behaves as [ADVANCED]). */
    ULTRA,
}
