package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Per-frame render input shared with display surfaces. */
@DreamDisplaysUnstableApi interface RenderContext {
    /** Partial tick / frame interpolation value. */
    val tickDelta: Float

    /** Camera X position in world coordinates. */
    val cameraX: Double

    /** Camera Y position in world coordinates. */
    val cameraY: Double

    /** Camera Z position in world coordinates. */
    val cameraZ: Double
}
