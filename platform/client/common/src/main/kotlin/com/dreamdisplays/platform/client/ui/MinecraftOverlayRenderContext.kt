package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.overlay.OverlayRenderContext
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

/**
 * Minecraft-backed [OverlayRenderContext]. This is the platform adapter that lets the
 * platform-agnostic [com.dreamdisplays.platform.client.overlay.Overlay] / [com.dreamdisplays.platform.client.overlay.OverlayManager]
 * contracts drive the existing Minecraft HUD rendering: the contract methods receive an
 * [OverlayRenderContext], implementations cast it back to this type to reach the live
 * [Minecraft] instance, [graphics] sink, and polled mouse state.
 *
 * Screen dimensions and scale are read live from the game window so the values are always current
 * for the frame being rendered.
 */
class MinecraftOverlayRenderContext(
    val mc: Minecraft,
    //? if >=26 {
    val graphics: GuiGraphicsExtractor,
    //?} else
    /*val graphics: GuiGraphics,*/
    val mouseX: Int,
    val mouseY: Int,
    val leftPressed: Boolean,
    override val partialTick: Float,
) : OverlayRenderContext {
    override val screenWidth: Int get() = mc.window.guiScaledWidth
    override val screenHeight: Int get() = mc.window.guiScaledHeight
    override val scaleFactor: Double get() = mc.window.guiScale.toDouble()
}
