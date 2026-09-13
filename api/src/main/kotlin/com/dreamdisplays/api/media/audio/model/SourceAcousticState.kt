package com.dreamdisplays.api.media.audio.model

import com.dreamdisplays.api.Unstable

/**
 * Latest known geometry and mix state for one registered source, published every game tick.
 *
 * @since 1.9.x
 */
@Unstable
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

    /** Latest raytraced acoustic space (occlusion + reverb); defaults to OPEN_AIR. */
    val environment: AcousticEnvironment = AcousticEnvironment.OPEN_AIR,
)
