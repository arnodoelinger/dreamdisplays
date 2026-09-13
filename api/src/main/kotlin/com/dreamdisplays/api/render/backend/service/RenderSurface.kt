package com.dreamdisplays.api.render.backend.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.property.DisplayBounds
import com.dreamdisplays.api.render.texture.model.TextureHandle

/**
 * Renderable display plane with its current texture and visibility state.
 *
 * @since 1.8.x
 */
@Unstable
interface RenderSurface {
    /** World-space display bounds. */
    val bounds: DisplayBounds

    /** Texture currently backing this surface. */
    val textureHandle: TextureHandle

    /** Brightness multiplier applied by the renderer. */
    val brightness: Float

    /** False when the surface should be skipped without unregistering it. */
    val isVisible: Boolean

    /** Renders this surface using [context]. */
    fun render(context: RenderContext)
}
