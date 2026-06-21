package com.dreamdisplays.api.render

import com.dreamdisplays.api.display.model.DisplayBounds

interface RenderSurface {
    val bounds: DisplayBounds
    val textureHandle: TextureHandle
    val brightness: Float
    val isVisible: Boolean

    fun render(context: RenderContext)
}
