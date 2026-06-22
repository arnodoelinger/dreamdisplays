package com.dreamdisplays.api.render

/** Registry and dispatcher for display render surfaces in one render context. */
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
