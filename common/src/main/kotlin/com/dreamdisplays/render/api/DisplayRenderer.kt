package com.dreamdisplays.render.api

interface DisplayRenderer {
    fun register(surface: RenderSurface)
    fun unregister(surface: RenderSurface)
    fun renderAll(context: RenderContext)
    val registeredCount: Int
    val stats: RenderStats
}
