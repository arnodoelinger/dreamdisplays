package com.dreamdisplays.mixins

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.client.ui.MinecraftOverlayRenderContext
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Mixin that injects PiP overlay rendering at the tail of every Screen render call. */
@Suppress("UNUSED")
@Mixin(Screen::class)
open class ScreenOverlay {
    // Renders all active PiP overlays on top of the current screen after the normal render pass.
    //? if >=26 {
    @Inject(
        method = ["extractRenderStateWithTooltipAndSubtitles"],
        at = [At("RETURN")]
    )
    open fun onRenderReturn(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {
    //?} else
    /*@Inject(
        method = ["renderWithTooltipAndSubtitles"],
        at = [At("RETURN")],
        require = 0
    )
    open fun onRenderReturn(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {*/
        val overlays = DreamServices.registry.getOrNull<OverlayManager>() ?: return
        if (overlays.isEmpty) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val leftPressed = GLFW.glfwGetMouseButton(mc.window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        overlays.renderAll(MinecraftOverlayRenderContext(mc, graphics, mouseX, mouseY, leftPressed, partialTick))
    }
}
