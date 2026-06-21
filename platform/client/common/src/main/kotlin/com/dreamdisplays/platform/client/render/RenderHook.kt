package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.RenderContext

/**
 * A hook that can be registered to be called during the rendering process. This allows for custom rendering logic to be
 * executed at specific points in the rendering pipeline.
 */
fun interface RenderHook {
    fun onRender(context: RenderContext)
}
