package com.dreamdisplays.platform.server.listeners

//? if >=26 {
import net.neoforged.neoforge.event.level.block.BreakBlockEvent as NeoForgeBreakEvent
//?} else
/*import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent as NeoForgeBreakEvent*/
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * Listener for protecting display areas from modifications.
 * Handles block breaking, explosions, and piston movements.
 */
@Suppress("UNUSED")
@PaperOnly
class ProtectionListener : Listener {
    /**
     * Handles block breaking events, checking if the block is protected and canceling if necessary.
     */
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val loc = event.block.location
        if (DisplayManager.isContains(loc) != null && PlayerManager.getVersion(event.player) == null) {
            MessageUtil.sendMessage(event.player, "displayBlockBreak")
        }
        cancelIfProtected(loc, event)
    }

    /**
     * Handles explosion events, removing protected blocks from the list of blocks to be destroyed.
     */
    @EventHandler
    fun onExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf { isLocationProtected(it.location) }
    }

    /**
     * Handles piston movements, checking if the piston is moving blocks protected by displays.
     */
    @EventHandler
    fun onPistonExtend(event: BlockPistonExtendEvent) = handlePiston(event.blocks, event)

    /**
     * Handles piston retraction, checking if the piston is moving blocks protected by displays.
     */
    @EventHandler
    fun onPistonRetract(event: BlockPistonRetractEvent) = handlePiston(event.blocks, event)

    /**
     * Handles piston movements, checking if the piston is moving blocks protected by displays.
     */
    private fun handlePiston(blocks: List<Block>, event: Cancellable) {
        if (event.isCancelled) return
        if (blocks.any { isLocationProtected(it.location) }) event.isCancelled = true
    }

    /**
     * Handles piston movements, checking if the piston is moving blocks protected by displays.
     */
    private fun cancelIfProtected(loc: Location, event: Cancellable) {
        if (isLocationProtected(loc)) event.isCancelled = true
    }

    /**
     * Checks if a location is in a protected area, considering both existing displays and current selections.
     */
    private fun isLocationProtected(loc: Location): Boolean =
        DisplayManager.isContains(loc) != null || SelectionManager.isLocationSelected(loc)
}

/**
 * Shared `Fabric` / `NeoForge` block-break protection check. [FabricProtectionListener] and
 * [NeoForgeProtectionListener] only adapt their loader's event API and hand off here.
 */
object VanillaProtectionListener {
    /** Returns true if the break at [pos] in [worldKey] should be cancelled; warns [player] if known. */
    fun handleBreak(worldKey: String, pos: BlockPos, player: ServerPlayer?): Boolean {
        val isProtected = DisplayManager.isContains(worldKey, pos) != null ||
                SelectionManager.isLocationSelected(pos, worldKey)

        if (isProtected && player != null && PlayerManager.getVersion(player.uuid) == null) {
            MessageUtil.sendMessage(player, "displayBlockBreak")
        }
        return isProtected
    }
}

/** `Fabric` event adapter for [VanillaProtectionListener]. */
@FabricOnly
object FabricProtectionListener {
    /**
     * Registers the protection listener. In `Fabric`, this is done automatically by the `Fabric API`, so we don't need to
     * register it, yey.
     */
    fun register() {
        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, _, _ ->
            val worldKey = RegionUtil.getLevelKey(world as ServerLevel)
            !VanillaProtectionListener.handleBreak(worldKey, pos, player as? ServerPlayer)
        }
    }
}

/** `NeoForge` event adapter for [VanillaProtectionListener]. */
@NeoForgeOnly
object NeoForgeProtectionListener {
    /** Cancels the break and (for known players) warns them when the block is inside a protected area. */
    @SubscribeEvent
    fun onBreak(event: NeoForgeBreakEvent) {
        val level = event.level as? ServerLevel ?: return
        val worldKey = RegionUtil.getLevelKey(level)
        if (VanillaProtectionListener.handleBreak(worldKey, event.pos, event.player as? ServerPlayer)) {
            event.setCanceled(true)
        }
    }
}
