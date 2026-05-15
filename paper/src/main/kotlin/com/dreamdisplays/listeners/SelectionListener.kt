package com.dreamdisplays.listeners

import com.dreamdisplays.Main
import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.SelectionManager.resetSelection
import com.dreamdisplays.managers.SelectionManager.selectionPoints
import com.dreamdisplays.managers.SelectionManager.setFirstPoint
import com.dreamdisplays.managers.SelectionManager.setSecondPoint
import com.dreamdisplays.managers.SelectionVisualizer.startParticleTask
import com.dreamdisplays.utils.MessageUtil
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.Action.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot.HAND

/**
 * Listener for player interactions to manage selection points for display creation.
 *
 * Players can set selection points by interacting with blocks while holding the designated selection material.
 * Left-clicking sets the first point, right-clicking sets the second point.
 * Sneaking and right-clicking resets the selection.
 */
class SelectionListener(plugin: Main) : Listener {

    init {
        startParticleTask(plugin)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return
        val heldItem = player.inventory.itemInMainHand.type

        if (player.isSneaking && event.action.isRightClick) {
            if (selectionPoints.containsKey(player.uniqueId)) {
                resetSelection(player)
                MessageUtil.sendMessage(player, "selectionClear")
            }
            return
        }

        if (heldItem != config.settings.selectionMaterial || block.type != config.settings.baseMaterial) return
        event.isCancelled = true

        when (event.action) {
            LEFT_CLICK_BLOCK -> setFirstPoint(player, block.location, event.blockFace)
            RIGHT_CLICK_BLOCK -> setSecondPoint(player, block.location)
            else -> {}
        }
    }

    private val Action.isRightClick get() = this in listOf(RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK)
}
