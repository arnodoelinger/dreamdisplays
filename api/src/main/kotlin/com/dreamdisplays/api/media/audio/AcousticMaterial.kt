package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Per-block-material acoustic coefficients used by the platform-side voxel probe when it raytraces a
 * source's surroundings. [reflectivity] scales how much energy a reflection ray carries onward (hard
 * stone reflects, wool absorbs); [occlusion] is the muffling weight a solid block contributes to a
 * blocked direct path. Values follow the well-established Sound-Physics material scale so tuning
 * intuition carries over, but the table that maps game blocks to materials lives on the platform side
 * (it needs the game's block/sound-type registry), not here.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
data class AcousticMaterial(
    /** Reflected-energy factor: 0 = fully absorbent, ~1.5 = hard reflective (stone/metal). */
    val reflectivity: Float,

    /** Muffling weight a full solid block of this material adds to an occluded direct path. */
    val occlusion: Float,
) {
    companion object {
        /** Fallback for unmapped blocks — mid reflectivity, one full occlusion unit. */
        val DEFAULT = AcousticMaterial(reflectivity = 0.5f, occlusion = 1.0f)

        /** Open air / non-solid: no reflection, no occlusion. */
        val AIR = AcousticMaterial(reflectivity = 0.0f, occlusion = 0.0f)
    }
}
