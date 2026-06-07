package com.dreamdisplays.mixins

import com.dreamdisplays.managers.ClientStateManager
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/** Mixin that suppresses crosshair rendering while the player is looking at a display screen. */
@Suppress("UNUSED")
@Pseudo
@Mixin(targets = ["net.minecraft.client.gui.Gui", "net.minecraft.client.gui.Hud"])
open class Crosshair {
    /** Cancels crosshair extraction when the player is targeting a display surface. */
    @Inject(method = ["extractCrosshair"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun extractCrosshair(
        guiGraphics: GuiGraphicsExtractor,
        deltaTracker: DeltaTracker,
        ci: CallbackInfo
    ) {
        if (ClientStateManager.isOnScreen) {
            ci.cancel()
        }
    }
}
