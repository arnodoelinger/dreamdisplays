package com.dreamdisplays.platform.client.ui

//? if >=26 {
import com.dreamdisplays.api.playback.FullscreenAckAction
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.core.protocol.FullscreenAck
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

/**
 * Coordinator for the fullscreen video overlay. At most one overlay is live at a time (a newly
 * opened one fades the previous out); closing overlays keep rendering until their animation ends.
 * Rendered before [PipOverlayManager] in the HUD hooks so PiP stays on top.
 */
object FullscreenOverlayManager {
    /** Live plus still-fading-out overlays, in open order. */
    private val overlays = mutableListOf<FullscreenOverlay>()

    /** Tracks whether a GUI screen was open last tick, for Esc (pause-screen) edge detection. */
    private var screenWasOpen = false

    /** The single non-closing overlay, or null when none is active. */
    val active: FullscreenOverlay? get() = overlays.lastOrNull { !it.isClosing }

    /** True when any overlay (including a fading-out one) still needs rendering. */
    val isEmpty: Boolean get() = overlays.isEmpty()

    /** True while an immersive-mode overlay is active; used to suppress the crosshair. */
    val isImmersiveActive: Boolean
        get() = active?.mode == FullscreenMode.IMMERSIVE

    /** Opens a fullscreen overlay for [screen], fading out any previous one. */
    fun open(screen: DisplayScreen, mode: FullscreenMode, forced: Boolean = false, sessionId: String? = null): FullscreenOverlay {
        overlays.forEach { it.startClose() }
        val overlay = FullscreenOverlay(screen, mode, forced, sessionId)
        overlays.add(overlay)
        return overlay
    }

    /** Starts the close animation for the active overlay belonging to [ds], if any. */
    fun remove(ds: DisplayScreen) {
        overlays.filter { it.displayScreen === ds }.forEach { it.startClose() }
    }

    /** Gracefully closes every overlay. */
    fun closeAll() {
        overlays.forEach { it.startClose() }
    }

    /** Immediately destroys all overlays without animation. */
    fun clear() {
        val mc = Minecraft.getInstance()
        overlays.forEach { it.cleanup(mc) }
        overlays.clear()
    }

    /**
     * Per-tick Esc handling: pressing Esc during gameplay opens the vanilla pause screen, so a
     * `null -> pause screen` transition while an overlay is active is treated as "Esc pressed on
     * the fullscreen overlay" – the pause screen is dismissed and the overlay closes (or minimizes
     * to PiP when forced).
     */
    fun onClientTick(mc: Minecraft) {
        val screen = MinecraftScreenUtil.currentScreen(mc)
        val overlay = active
        if (overlay != null && !screenWasOpen && screen != null && screen.isPauseScreen) {
            MinecraftScreenUtil.setScreen(mc, null)
            if (overlay.forced) {
                overlay.displayScreen.minimizeFullscreenToPip()
                overlay.sessionId?.let { ProtocolRouter.send(FullscreenAck(it, FullscreenAckAction.MINIMIZED.wire)) }
            } else {
                overlay.displayScreen.deactivateFullscreen()
                overlay.sessionId?.let { ProtocolRouter.send(FullscreenAck(it, FullscreenAckAction.DISMISSED.wire)) }
            }
        }
        screenWasOpen = screen != null
    }

    /** Uploads and renders all overlays, dropping ones whose close animation has finished. */
    //? if >=26 {
    fun renderAll(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        //?} else
        /*fun renderAll(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {*/
        val iter = overlays.iterator()
        while (iter.hasNext()) {
            val overlay = iter.next()
            overlay.uploadFrame()
            if (!overlay.render(mc, graphics, partialTick)) iter.remove()
        }
    }
}
