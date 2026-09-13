package com.dreamdisplays.api.media.audio.model

import com.dreamdisplays.api.Unstable

/**
 * Acoustic coefficients per block material; [reflectivity] and [occlusion].
 *
 * @since 1.9.x
 */
@Unstable
data class AcousticMaterial(
    /** Reflected-energy factor: 0 = fully absorbent, ~1.5 = hard reflective (stone / metal). */
    val reflectivity: Float,

    /** Muffling weight a full solid block of this material adds to an occluded direct path. */
    val occlusion: Float,
) {
    companion object {
        /** Fallback for unmapped blocks — mid-reflectivity, one full occlusion unit. */
        val DEFAULT = AcousticMaterial(reflectivity = 0.5f, occlusion = 1.0f)

        /** Open air / non-solid: no reflection, no occlusion. */
        val AIR = AcousticMaterial(reflectivity = 0.0f, occlusion = 0.0f)
    }
}
