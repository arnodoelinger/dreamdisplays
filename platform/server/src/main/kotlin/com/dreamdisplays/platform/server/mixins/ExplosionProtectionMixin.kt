package com.dreamdisplays.platform.server.mixins

//? if >=1.21.11 {
import net.minecraft.world.level.ServerExplosion
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
//?} else
/*import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo*/
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject

@Suppress("NonJavaMixin")
//? if >=1.21.11 {
@Mixin(ServerExplosion::class)
open class ExplosionProtectionMixin {
    @Shadow
    open fun level(): ServerLevel = throw AssertionError()

    @Inject(method = ["calculateExplodedPositions"], at = [At("RETURN")], require = 1)
    open fun dd_filterExplodedBlocks(cir: CallbackInfoReturnable<MutableList<BlockPos>>) {
        val worldKey = RegionUtil.getLevelKey(level())
        cir.returnValue.removeIf { pos ->
            DisplayManager.isContains(worldKey, pos) != null || SelectionManager.isLocationSelected(pos, worldKey)
        }
    }
}
//?} else
/*@Mixin(Explosion::class)
open class ExplosionProtectionMixin {
    @Shadow
    @JvmField
    var level: Level? = null

    @Shadow
    open fun getToBlow(): MutableList<BlockPos> = throw AssertionError()

    @Inject(method = ["finalizeExplosion"], at = [At("HEAD")], require = 1)
    open fun dd_filterExplodedBlocks(spawnParticles: Boolean, ci: CallbackInfo) {
        val serverLevel = level as? ServerLevel ?: return
        val worldKey = RegionUtil.getLevelKey(serverLevel)
        getToBlow().removeIf { pos ->
            DisplayManager.isContains(worldKey, pos) != null || SelectionManager.isLocationSelected(pos, worldKey)
        }
    }
}*/
