package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.overlay.Overlay
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.overlay.OverlayRenderContext
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/

/**
 * Minecraft-backed [OverlayRenderContext]. This is the platform adapter that lets the platform-agnostic [OverlayManager]
 * draw against Minecraft's per-frame GUI draw target.
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
