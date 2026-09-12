package com.dreamdisplays.api.render.backend.service

import com.dreamdisplays.api.Unstable

/**
 * Per-frame render input shared with display surfaces.
 *
 * @since 1.8.x
 */
@Unstable
interface RenderContext {
    /** Partial tick / frame interpolation value. */
    val tickDelta: Float

    /** Camera X position in world coordinates. */
    val cameraX: Double

    /** Camera Y position in world coordinates. */
    val cameraY: Double

    /** Camera Z position in world coordinates. */
    val cameraZ: Double
}
