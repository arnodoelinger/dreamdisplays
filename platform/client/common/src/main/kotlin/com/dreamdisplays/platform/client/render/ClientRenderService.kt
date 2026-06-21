package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.render.RenderContext
import com.dreamdisplays.api.render.TextureHandle

/**
 * Service for rendering displays on the client.
 */
interface ClientRenderService {
    /** Registers a render hook to be called during the rendering process. */
    fun registerDisplay(entry: DisplayRenderEntry)

    /** Unregisters a display, removing it from the rendering process. */
    fun unregisterDisplay(displayId: DisplayId)

    /** Updates the texture for a specific display. The display must already be registered. */
    fun updateTexture(displayId: DisplayId, handle: TextureHandle)

    /** Renders all registered displays. This is called by the rendering system and should not be called directly. */
    fun renderAll(context: RenderContext)

    /** Number of live displays with an uploaded texture. Those this renderer will actually draw. */
    val registeredCount: Int
}
