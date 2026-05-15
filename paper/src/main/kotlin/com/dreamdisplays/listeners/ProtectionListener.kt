package com.dreamdisplays.listeners

import com.dreamdisplays.managers.DisplayManager.isContains
import com.dreamdisplays.managers.PlayerManager
import com.dreamdisplays.managers.SelectionManager.isLocationSelected
import com.dreamdisplays.utils.MessageUtil.sendMessage
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
class ProtectionListener : Listener {
    // Problem: player tries to break a block in a protected area
    // Solution: cancel event entirely
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val loc = event.block.location
        if (isContains(loc) != null && PlayerManager.getVersion(event.player) == null) {
            sendMessage(event.player, "displayBlockBreak")
        }
        cancelIfProtected(loc, event)
    }

    // Problem: explosion tries to destroy blocks in a protected area
    // Solution: remove protected blocks from the list of blocks to be destroyed
    @EventHandler
    fun onExplosion(event: EntityExplodeEvent) {
        event.blockList().removeIf { isLocationProtected(it.location) }
    }

    // Problem: piston tries to move blocks in a protected area
    // Solution: cancel event entirely
    @EventHandler
    fun onPistonExtend(event: BlockPistonExtendEvent) = handlePiston(event.blocks, event)

    // Problem: piston tries to move blocks in a protected area
    // Solution: cancel event entirely
    @EventHandler
    fun onPistonRetract(event: BlockPistonRetractEvent) = handlePiston(event.blocks, event)

    // Problem: some blocks are in a protected area and piston is trying to move them
    // Solution: cancel event entirely
    private fun handlePiston(blocks: List<Block>, event: Cancellable) {
        if (event.isCancelled) return
        if (blocks.any { isLocationProtected(it.location) }) event.isCancelled = true
    }

    // Problem: location is in a protected area
    // Solution: cancel the event
    private fun cancelIfProtected(loc: Location, event: Cancellable) {
        if (isLocationProtected(loc)) event.isCancelled = true
    }

    // Problem: check if a location is in a protected area
    // Solution: check both existing displays and current selections
    private fun isLocationProtected(loc: Location): Boolean =
        isContains(loc) != null || isLocationSelected(loc)

    // TODO: add protection for other events like fire spread (if admin has set flammable material for displays)
    // and fluid flow (but I'm not sure if it's in general necessary)
}
