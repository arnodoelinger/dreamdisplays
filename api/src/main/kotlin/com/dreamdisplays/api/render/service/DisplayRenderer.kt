package com.dreamdisplays.api.render.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.render.backend.service.RenderContext
import com.dreamdisplays.api.render.backend.service.RenderSurface
import com.dreamdisplays.api.render.model.RenderStats

/**
 * Registry and dispatcher for display render surfaces in one render context.
 *
 * @since 1.8.x
 */
@Unstable
interface DisplayRenderer {
    /** Adds [surface] to the render set. */
    fun register(surface: RenderSurface)

    /** Removes [surface] from the render set. */
    fun unregister(surface: RenderSurface)

    /** Renders all registered visible surfaces with [context]. */
    fun renderAll(context: RenderContext)

    /** Number of currently registered surfaces. */
    val registeredCount: Int

    /** Latest aggregate render / upload statistics. */
    val stats: RenderStats
}
