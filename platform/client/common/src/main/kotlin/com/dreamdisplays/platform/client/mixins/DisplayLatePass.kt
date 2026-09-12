package com.dreamdisplays.platform.client.mixins

import com.dreamdisplays.platform.client.render.UnshadedDisplayPass
import net.minecraft.client.DeltaTracker
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Draws the displays once the level pass is over, past every shader-pack program and composite
 * stage, while the main render target still carries the world depth buffer. See [UnshadedDisplayPass].
 */
@Suppress("NonJavaMixin")
@Mixin(GameRenderer::class)
open class DisplayLatePass {
    @Inject(
        method = ["renderLevel"],
        at = [At(value = "CONSTANT", args = ["stringValue=hand"])],
        require = 0,
    )
    open fun onLevelRendered(deltaTracker: DeltaTracker, ci: CallbackInfo) {
        UnshadedDisplayPass.drawPending()
    }
}
