package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Result of a platform-side voxel raytrace describing the acoustic space between one source and the
 * listener, in engine-agnostic terms.
 *
 * All fields are normalized / in seconds so the DSP chain can smooth and map them directly onto its
 * occlusion filter and reverb send without knowing anything about blocks.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
data class AcousticEnvironment(
    /** Direct-path blockage: 0 = clear line of sight, 1 = fully walled off (max muffling + drop). */
    val occlusion: Float,

    /** Estimated reverberation decay (RT60-like), in seconds. 0 disables the reverb tail. */
    val reverbDecaySeconds: Float,

    /** Wet-mix level of the reverb send, 0 (anechoic / open sky) .. 1 (fully enclosed reflective space). */
    val reverbWetGain: Float,

    /** High-frequency damping of the reverb tail, 0 (bright, hard walls) .. 1 (dark, soft walls). */
    val reverbDamping: Float,
) {
    companion object {
        /** Outdoors, unobstructed: no occlusion and a dry (reverb-free) signal. */
        val OPEN_AIR = AcousticEnvironment(
            occlusion = 0f,
            reverbDecaySeconds = 0f,
            reverbWetGain = 0f,
            reverbDamping = 0f,
        )
    }
}
