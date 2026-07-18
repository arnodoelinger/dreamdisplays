package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplays.platform.client.ui.MinecraftOverlayRenderContext
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

/** Mixin that injects PiP overlay rendering at the tail of every Screen render call. Unsafe to change. */
@Suppress("UNUSED", "NonJavaMixin")
@Mixin(Screen::class)
open class ScreenOverlay {
    //? if >=26 {
    @Inject(
        method = ["extractRenderStateWithTooltipAndSubtitles"],
        at = [At("HEAD")]
    )
    open fun onRenderHead(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
        ci: CallbackInfo
    ) {
        //?} else
        /*@Inject(
            //? if >=1.21.11 {
            method = ["renderWithTooltipAndSubtitles"],
            //?}
            //? if <1.21.11 {
            method = ["renderWithTooltip"],
            //?}
            at = [At("HEAD")],
            require = 0
        )
        open fun onRenderHead(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {*/
        if (FullscreenOverlayManager.isEmpty) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        FullscreenOverlayManager.onClientTick(mc)
        if (FullscreenOverlayManager.isEmpty) return
        FullscreenOverlayManager.renderAll(mc, graphics, partialTick)
    }

    // Renders all active PiP overlays on top of the current screen after the normal render pass
    //? if >=26 {
    @Inject(
        method = ["extractRenderStateWithTooltipAndSubtitles"],
        at = [At("RETURN")]
    )
    open fun onRenderReturn(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
        ci: CallbackInfo
    ) {
        //?} else
        /*@Inject(
            // 1.21.11 renders screens through renderWithTooltipAndSubtitles; 1.21.1 has no such method.
            // Use renderWithTooltip there (the final wrapper that calls render() then draws the deferred
            // tooltip) so the PiP lands on top of tooltips instead of under them.
            //? if >=1.21.11 {
            method = ["renderWithTooltipAndSubtitles"],
            //?}
            //? if <1.21.11 {
            method = ["renderWithTooltip"],
            //?}
            at = [At("RETURN")],
            require = 0
        )
        open fun onRenderReturn(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {*/
        val overlays = DreamServices.registry.getOrNull<OverlayManager>() ?: return
        if (overlays.isEmpty) return
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) return
        val window =
            //? if >=1.21.11 {
            mc.window.handle()
            //?} else
            /*mc.window.window*/
        val leftPressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        //? if >=1.21.11 {
        graphics.nextStratum()
        //?}
        overlays.renderAll(MinecraftOverlayRenderContext(mc, graphics, mouseX, mouseY, leftPressed, partialTick))
    }
}
