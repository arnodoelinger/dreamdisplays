package com.dreamdisplays.api.media.audio.model

import com.dreamdisplays.api.Unstable

/**
 * Acoustic space between source and listener; engine-agnostic, normalized for DSP mapping.
 *
 * @since 1.9.x
 */
@Unstable
data class AcousticEnvironment(
    /** Direct-path blockage: 0 = clear line of sight, 1 = fully walled off (max muffling + drop). */
    val occlusion: Float,

    /** Estimated reverberation decay (RT60-like), in seconds. 0 disables the reverb tail. */
    val reverbDecaySeconds: Float,

    /** Wet-mix level of the reverb send, 0 (anechoic / open sky) ..1 (fully enclosed reflective space). */
    val reverbWetGain: Float,

    /** High-frequency damping of the reverb tail, 0 (bright, hard walls) ..1 (dark, soft walls). */
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
