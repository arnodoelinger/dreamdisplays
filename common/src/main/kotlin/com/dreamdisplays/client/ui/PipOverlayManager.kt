package com.dreamdisplays.client.ui

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.client.overlay.Overlay
import com.dreamdisplays.client.overlay.OverlayEvent
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.client.overlay.OverlayRenderContext
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

/**
 * Coordinator for all active PiP overlays. Each overlay manages its own input / state;
 * the manager only sequences rendering and prevents two overlays from occupying the
 * same anchor.
 */
object PipOverlayManager : OverlayManager {
    private val overlays = mutableListOf<PipOverlay>()

    /** Adds the overlay, snapping to a free anchor if the requested one is taken. Returns false if all 8 anchors are occupied. */
    fun add(overlay: PipOverlay): Boolean {
        if (overlays.size >= PipAnchor.entries.size) return false
        val taken = overlays.map { it.anchor }.toSet()
        if (overlay.anchor in taken) {
            val free = PipAnchor.entries.firstOrNull { it !in taken } ?: return false
            overlay.anchor = free
        }
        overlays.add(overlay)
        return true
    }

    /** Starts the close animation for any overlay belonging to [ds]. */
    fun remove(ds: DisplayScreen) {
        overlays.filter { it.displayScreen === ds }.forEach { it.startClose() }
    }

    /** Returns true if [anchor] is free (or already owned by [ds]). */
    fun canUseAnchor(ds: PipOverlay, anchor: PipAnchor): Boolean =
        overlays.none { it !== ds && it.anchor == anchor && !it.isDragging }

    /** Renders all overlays and forwards mouse state for in-overlay input handling. */
    //? if >=26 {
    fun renderAll(
        mc: Minecraft, graphics: GuiGraphicsExtractor,
        mouseX: Int, mouseY: Int, leftPressed: Boolean, partialTick: Float,
    ) {
    //?} else
    /*fun renderAll(
        mc: Minecraft, graphics: GuiGraphics,
        mouseX: Int, mouseY: Int, leftPressed: Boolean, partialTick: Float,
    ) {*/
        val iter = overlays.iterator()
        while (iter.hasNext()) {
            val overlay = iter.next()
            overlay.uploadFrame()
            if (!overlay.render(mc, graphics, mouseX, mouseY, leftPressed, partialTick)) iter.remove()
        }
    }

    /** Immediately destroys all overlays. */
    fun clear() {
        val mc = Minecraft.getInstance()
        overlays.forEach { it.cleanup(mc) }
        overlays.clear()
    }

    override val isEmpty: Boolean get() = overlays.isEmpty()

    /**
     * Opens (or returns the already-open) PiP overlay for [displayId]. Returns null if no display
     * screen with that id is currently loaded, or if all anchors are occupied.
     */
    override fun openPip(displayId: DisplayId): Overlay? {
        getOverlay(displayId)?.let { return it }
        val screen = DisplayRegistry.screens[displayId.uuid] ?: return null
        val overlay = PipOverlay(screen)
        return if (add(overlay)) overlay else null
    }

    /** Starts the close animation for the overlay bound to [displayId], if any. */
    override fun closePip(displayId: DisplayId) {
        overlays.filter { it.displayId == displayId }.forEach { it.startClose() }
    }

    /** Returns the live overlay for [displayId], or null if none is open. */
    override fun getOverlay(displayId: DisplayId): Overlay? =
        overlays.firstOrNull { it.displayId == displayId }

    /** Snapshot of all currently open overlays. */
    override fun listOverlays(): List<Overlay> = overlays.toList()

    /**
     * [OverlayManager] render entry point. Delegates to the Minecraft render path by unwrapping a
     * [MinecraftOverlayRenderContext]; any other context type is a no-op.
     */
    override fun renderAll(context: OverlayRenderContext) {
        val ctx = context as? MinecraftOverlayRenderContext ?: return
        renderAll(ctx.mc, ctx.graphics, ctx.mouseX, ctx.mouseY, ctx.leftPressed, ctx.partialTick)
    }

    /**
     * Routes [event] to the top-most overlay whose bounds contain (`atX`, `atY`); iterates in
     * reverse so the most-recently-added (visually front) overlay wins.
     * @return true if an overlay consumed the event.
     */
    override fun dispatchEvent(event: OverlayEvent, atX: Float, atY: Float): Boolean {
        for (i in overlays.indices.reversed()) {
            val overlay = overlays[i]
            if (overlay.bounds.contains(atX, atY) && overlay.onEvent(event)) return true
        }
        return false
    }

    /** Gracefully closes every overlay (animated), as opposed to the immediate [clear]. */
    override fun closeAll() {
        overlays.forEach { it.startClose() }
    }
}
