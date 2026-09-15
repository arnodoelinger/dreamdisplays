package com.dreamdisplays.platform.server.mixins

import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.piston.PistonStructureResolver
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Suppress("NonJavaMixin")
@Mixin(PistonStructureResolver::class)
open class PistonProtectionMixin {
    @Shadow
    @JvmField
    var level: Level? = null

    @Shadow
    open fun getToPush(): MutableList<BlockPos> = throw AssertionError()

    @Shadow
    open fun getToDestroy(): MutableList<BlockPos> = throw AssertionError()

    @Inject(method = ["resolve"], at = [At("RETURN")], cancellable = true, require = 1)
    open fun dd_protectPistonStructure(cir: CallbackInfoReturnable<Boolean>) {
        if (cir.returnValue != true) return
        val serverLevel = level as? ServerLevel ?: return
        val worldKey = RegionUtil.getLevelKey(serverLevel)
        val isProtected = { pos: BlockPos ->
            DisplayManager.isContains(worldKey, pos) != null || SelectionManager.isLocationSelected(pos, worldKey)
        }
        if (getToPush().any(isProtected) || getToDestroy().any(isProtected)) {
            cir.returnValue = false
        }
    }
}
