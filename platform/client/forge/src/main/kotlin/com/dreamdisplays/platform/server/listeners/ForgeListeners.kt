package com.dreamdisplays.platform.server.listeners

import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.RegionUtil
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
//? if >=1.19 {
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.event.level.PistonEvent
//?} else
/*import net.minecraftforge.event.world.BlockEvent
import net.minecraftforge.event.world.ExplosionEvent
import net.minecraftforge.event.world.PistonEvent*/
import net.minecraftforge.eventbus.api.SubscribeEvent

/** `Forge` event-subscription adapter for [VanillaPlayerListener]. */
object ForgePlayerConnectionListener {
    @SubscribeEvent
    fun onLogin(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onJoin(player, RegionUtil.playerServer(player))
    }

    @SubscribeEvent
    fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        VanillaPlayerListener.onLeave(player.uuid)
    }
}

/** `Forge` event adapter for [VanillaProtectionListener]. */
object ForgeProtectionListener {
    @SubscribeEvent
    fun onBreak(event: BlockEvent.BreakEvent) {
        val level = event.level as? ServerLevel ?: return
        val worldKey = RegionUtil.getLevelKey(level)
        if (VanillaProtectionListener.handleBreak(worldKey, event.pos, event.player as? ServerPlayer)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onExplosion(event: ExplosionEvent.Detonate) {
        val level = event.level as? ServerLevel ?: return
        VanillaProtectionListener.filterExplosionBlocks(RegionUtil.getLevelKey(level), event.affectedBlocks)
    }

    @SubscribeEvent
    fun onPiston(event: PistonEvent.Pre) {
        val level = event.level as? ServerLevel ?: return
        val structure = event.structureHelper ?: return
        if (!structure.resolve()) return
        val worldKey = RegionUtil.getLevelKey(level)
        val isProtected = { pos: BlockPos ->
            DisplayManager.isContains(worldKey, pos) != null
        }
        if (structure.toPush.any(isProtected) || structure.toDestroy.any(isProtected)) event.isCanceled = true
    }
}

object ForgeSelectionListener {
    @SubscribeEvent
    fun onLeftClick(event: PlayerInteractEvent.LeftClickBlock) {
        //? if >=1.20 {
        if (event.action != PlayerInteractEvent.LeftClickBlock.Action.START) return
        //?}
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return
        val face = event.face ?: return

        if (VanillaSelectionListener.handleLeftClick(player, world, event.pos, face)) event.isCanceled = true
    }

    @SubscribeEvent
    fun onRightClick(event: PlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return

        if (VanillaSelectionListener.handleRightClick(player, world, event.pos)) event.isCanceled = true
    }
}

//? if <1.19 {
private val BlockEvent.level get() = world
private val ExplosionEvent.level get() = world
private val PlayerInteractEvent.level get() = world
//?}
