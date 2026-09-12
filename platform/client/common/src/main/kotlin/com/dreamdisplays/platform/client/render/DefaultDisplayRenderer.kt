package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.service.DisplayRenderer
import com.dreamdisplays.api.render.backend.service.RenderContext
import com.dreamdisplays.api.render.model.RenderStats
import com.dreamdisplays.api.render.backend.service.RenderSurface
import java.util.concurrent.CopyOnWriteArrayList

/** Default [DisplayRenderer]: orchestrator for externally registered, self-rendering [RenderSurface]s. */
class DefaultDisplayRenderer : DisplayRenderer {
    /** Registered self-rendering surfaces, drawn each pass. */
    private val surfaces = CopyOnWriteArrayList<RenderSurface>()

    /** Start of the current one-second FPS measurement window. */
    private var frameWindowStartNanos = 0L

    /** Passes counted in the current FPS window. */
    private var frameWindowCount = 0

    /** Most recent measured pass rate (passes per second). */
    @Volatile
    private var measuredFps = 0f

    /** Duration of the last render pass, in milliseconds. */
    @Volatile
    private var lastPassMillis = 0L

    /** Adds [surface] to the render pass (a surface instance is never registered twice). */
    override fun register(surface: RenderSurface) {
        if (surface !in surfaces) surfaces.add(surface)
    }

    /** Removes [surface] from the render pass; no-op if it was never registered. */
    override fun unregister(surface: RenderSurface) {
        surfaces.remove(surface)
    }

    /** Renders every visible registered surface against [context] and updates [stats]. */
    override fun renderAll(context: RenderContext) {
        val start = System.nanoTime()
        surfaces.forEach { surface ->
            if (surface.isVisible) surface.render(context)
        }
        lastPassMillis = (System.nanoTime() - start) / 1_000_000L
        tickFpsWindow(start)
    }

    /** Number of registered surfaces. */
    override val registeredCount: Int
        get() = surfaces.size

    /** Orchestrator-level render stats (surface pass rate and last-pass latency only). */
    override val stats: RenderStats
        get() = RenderStats(
            decodedFps = 0f,
            uploadedFps = measuredFps,
            droppedFrames = 0,
            lastUploadLatencyMs = lastPassMillis,
            textureMemoryBytes = 0L,
        )

    /** Counts render passes over a sliding one-second window to derive [measuredFps]. */
    private fun tickFpsWindow(nowNanos: Long) {
        if (nowNanos - frameWindowStartNanos >= 1_000_000_000L) {
            measuredFps = frameWindowCount.toFloat()
            frameWindowStartNanos = nowNanos
            frameWindowCount = 0
        }
        frameWindowCount++
    }
}
