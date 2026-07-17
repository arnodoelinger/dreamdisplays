package com.dreamdisplays.api.media.audio

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Latest known geometry and mix state for one registered source, published every game tick.
 *
 * @since 1.9.0
 */
@DreamDisplaysUnstableApi
data class SourceAcousticState(
    /** Source plane */
    val plane: SourcePlane,

    /** User volume. */
    val userVolume: Float,

    /** Muted? */
    val muted: Boolean,

    /** True while played through a popout / fullscreen window, where distance no longer applies. */
    val bypassSpatial: Boolean,

    /** Higher-priority sources keep the full DSP chain first when the render budget is exceeded. */
    val priority: Int = 0,

    /** Per-display opt-out; false forces the [AcousticQuality.OFF] legacy path for this source. */
    val acousticsEnabled: Boolean = true,

    /**
     * Latest raytraced acoustic space between this source and the listener (occlusion + reverb).
     * Defaults to [AcousticEnvironment.OPEN_AIR], so a platform that never runs the voxel probe keeps
     * the pure-spatialization behavior with no environmental coloring.
     */
    val environment: AcousticEnvironment = AcousticEnvironment.OPEN_AIR,
)
