package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.ui.FullscreenOverlayManager
import net.minecraft.client.DeltaTracker
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor

//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Mixin that cancels individual vanilla HUD pieces. */
@Suppress("NonJavaMixin")
@Pseudo
//? if >=26 {
@Mixin(targets = ["net.minecraft.client.gui.Hud"])
//?} else
/*@Mixin(targets = ["net.minecraft.client.gui.Gui"])*/
open class HudSuppress {
    //? if >=26 {
    @Inject(method = ["extractHotbarAndDecorations"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHotbar(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractPlayerHealth"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHealth(g: GuiGraphicsExtractor, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractChat"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractChat(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractScoreboardSidebar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractScoreboard(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractTabList"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractTabList(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["extractBossOverlay"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractBossOverlay(g: GuiGraphicsExtractor, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)
    //?} else
    /*
    @Inject(method = ["renderHotbarAndDecorations"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHotbar(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderPlayerHealth"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractHealth(g: GuiGraphics, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderChat"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractChat(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderScoreboardSidebar"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractScoreboard(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderTabList"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractTabList(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)

    @Inject(method = ["renderBossOverlay"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractBossOverlay(g: GuiGraphics, dt: DeltaTracker, ci: CallbackInfo) = suppress(ci)
    */

    private fun suppress(ci: CallbackInfo) {
        if (FullscreenOverlayManager.shouldHideVanillaHud) ci.cancel()
    }
}
