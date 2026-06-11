package com.dreamdisplays.client.render

import com.dreamdisplays.render.api.RenderContext

/**
 * A hook that can be registered to be called during the rendering process. This allows for custom rendering logic to be
 * executed at specific points in the rendering pipeline.
 *
 * @since 1.8.0
 */
fun interface RenderHook {
    fun onRender(context: RenderContext)
}
