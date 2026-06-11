package com.dreamdisplays.render.api

import com.dreamdisplays.api.DisplayBounds

interface RenderSurface {
    val bounds: DisplayBounds
    val textureHandle: TextureHandle
    val brightness: Float
    val isVisible: Boolean

    fun render(context: RenderContext)
}
