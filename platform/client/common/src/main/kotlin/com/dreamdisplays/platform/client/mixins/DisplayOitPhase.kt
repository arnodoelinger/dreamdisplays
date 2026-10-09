package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.render.DisplaySolidPhase
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Pseudo
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Reports display render types as non-blending while `Improved Transparency` is on, so the game
 * queues them in the solid phase, and not in the OIT one.
 *
 * @see [DisplaySolidPhase]
 */
@Suppress("NonJavaMixin")
@Pseudo
@Mixin(targets = ["net.minecraft.client.renderer.rendertype.RenderType"])
open class DisplayOitPhase {
    @Inject(method = ["hasBlending"], at = [At("HEAD")], cancellable = true, require = 0)
    open fun keepDisplaysOutOfOit(cir: CallbackInfoReturnable<Boolean>) {
        if (DisplaySolidPhase.drawsSolid(this)) cir.returnValue = false
    }
}
