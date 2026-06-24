package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.render.DisplayRenderer
import com.dreamdisplays.api.render.RenderContext
import com.dreamdisplays.api.render.RenderStats
import com.dreamdisplays.api.render.RenderSurface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [DisplayRenderer]: an orchestrator for externally registered, self-rendering
 * [RenderSurface]s. The mod's own world screens keep their dedicated [ScreenRenderer] path; this
 * renderer sequences API-registered surfaces during the same world render pass (see the hook in
 * [ScreenRenderer.render]).
 *
 * [stats] reports only what an orchestrator can observe: the surface-pass rate and the duration of
 * the last pass. Decode / upload figures belong to the per-display video pipeline and stay zero here.
 */
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
