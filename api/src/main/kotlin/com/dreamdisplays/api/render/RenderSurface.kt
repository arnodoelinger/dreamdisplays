package com.dreamdisplays.api.render

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import com.dreamdisplays.api.display.model.DisplayBounds

/** Renderable display plane with its current texture and visibility state. */
@DreamDisplaysUnstableApi interface RenderSurface {
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
