package com.dreamdisplays.client.ui

import com.dreamdisplays.display.DisplayScreen
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
// TODO: rewrite this class entirely in 1.9.0
object PipOverlayManager {
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

    val isEmpty: Boolean get() = overlays.isEmpty()
}
