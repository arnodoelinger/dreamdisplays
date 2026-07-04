package com.dreamdisplays.platform.server.listeners

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.NeoForgeServer
import com.dreamdisplays.platform.server.Server
import com.dreamdisplays.platform.server.managers.NeoForgeSelectionManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.managers.SelectionVisualizer
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent as NeoForgePlayerInteractEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.Action.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Listener for player interactions to manage selection points for display creation.
 *
 * Players can set selection points by interacting with blocks while holding the designated selection material.
 * Left-clicking sets the first point, right-clicking sets the second point.
 * Sneaking and right-clicking resets the selection.
 */
@PaperOnly
class SelectionListener(plugin: Main) : Listener {
    init {
        SelectionVisualizer.startParticleTask(plugin)
    }

    /** Handles material interactions: left-click sets pos1, right-click sets pos2, shift-right resets. */
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val block = event.clickedBlock ?: return
        val heldItem = player.inventory.itemInMainHand.type

        if (player.isSneaking && event.action.isRightClick) {
            if (SelectionManager.selectionPoints.containsKey(player.uniqueId)) {
                SelectionManager.resetSelection(player)
                MessageUtil.sendMessage(player, "selectionClear")
            }
            return
        }

        if (heldItem != Main.config.settings.selectionMaterial || block.type != Main.config.settings.baseMaterial) return
        event.isCancelled = true

        when (event.action) {
            LEFT_CLICK_BLOCK -> SelectionManager.setFirstPoint(player, block.location, player.facingDirection())
            RIGHT_CLICK_BLOCK -> SelectionManager.setSecondPoint(player, block.location)
            else -> {}
        }
    }

    private val Action.isRightClick get() = this in listOf(RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK)

    private fun org.bukkit.entity.Player.facingDirection(): org.bukkit.block.BlockFace = when {
        location.pitch < -45f -> org.bukkit.block.BlockFace.DOWN
        location.pitch > 45f -> org.bukkit.block.BlockFace.UP
        else -> facing.oppositeFace
    }
}

/**
 * `Fabric`-specific implementation of [SelectionListener].
 */
@FabricOnly
object FabricSelectionListener {
    /** Registers the selection listener. */
    fun register() {
        AttackBlockCallback.EVENT.register { player, world, hand, pos, direction ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            if (player !is ServerPlayer) return@register InteractionResult.PASS
            if (world !is ServerLevel) return@register InteractionResult.PASS

            val config = Server.config
            val selMaterialKey = config.settings.selectionMaterial
            val heldItem = player.mainHandItem
            val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
            if (heldItemKey != selMaterialKey) return@register InteractionResult.PASS

            val baseMaterialKey = config.settings.baseMaterial
            val blockState = world.getBlockState(pos)
            val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
            if (blockKey != baseMaterialKey) return@register InteractionResult.PASS

            val worldKey = RegionUtil.getLevelKey(world)
            val face = Direction.orderedByNearest(player)[0].opposite
            SelectionManager.setFirstPoint(player, pos, worldKey, face)

            InteractionResult.SUCCESS
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            if (player !is ServerPlayer) return@register InteractionResult.PASS
            if (world !is ServerLevel) return@register InteractionResult.PASS

            val config = Server.config
            val selMaterialKey = config.settings.selectionMaterial
            val heldItem = player.mainHandItem
            val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
            if (heldItemKey != selMaterialKey) return@register InteractionResult.PASS

            val pos = hitResult.blockPos
            val worldKey = RegionUtil.getLevelKey(world)

            if (player.isShiftKeyDown) {
                if (SelectionManager.selectionPoints.containsKey(player.uuid)) {
                    SelectionManager.resetSelection(player)
                    MessageUtil.sendMessage(player, "selectionClear")
                }
                return@register InteractionResult.SUCCESS
            }

            val baseMaterialKey = config.settings.baseMaterial
            val blockState = world.getBlockState(pos)
            val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
            if (blockKey != baseMaterialKey) return@register InteractionResult.PASS

            SelectionManager.setSecondPoint(player, pos, worldKey)
            InteractionResult.SUCCESS
        }
    }

}

/**
 * `NeoForge`-specific implementation of [SelectionListener].
 */
@NeoForgeOnly
object NeoForgeSelectionListener {
    /** Left-click sets pos1. */
    @SubscribeEvent
    fun onLeftClick(event: NeoForgePlayerInteractEvent.LeftClickBlock) {
        if (event.action != NeoForgePlayerInteractEvent.LeftClickBlock.Action.START) return
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return

        val config = NeoForgeServer.config
        val selMaterialKey = config.settings.selectionMaterial
        val heldItem = player.mainHandItem
        val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
        if (heldItemKey != selMaterialKey) return

        val baseMaterialKey = config.settings.baseMaterial
        val pos = event.pos
        val blockState = world.getBlockState(pos)
        val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
        if (blockKey != baseMaterialKey) return

        val worldKey = RegionUtil.getLevelKey(world)
        val face = Direction.orderedByNearest(player)[0].opposite
        NeoForgeSelectionManager.setFirstPoint(player, pos, worldKey, face)
        event.setCanceled(true)
    }

    /** Right-click sets pos2; sneak + right-click resets. */
    @SubscribeEvent
    fun onRightClick(event: NeoForgePlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return

        val config = NeoForgeServer.config
        val selMaterialKey = config.settings.selectionMaterial
        val heldItem = player.mainHandItem
        val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
        if (heldItemKey != selMaterialKey) return

        val pos = event.pos
        val worldKey = RegionUtil.getLevelKey(world)

        if (player.isShiftKeyDown) {
            if (SelectionManager.selectionPoints.containsKey(player.uuid)) {
                NeoForgeSelectionManager.resetSelection(player)
                MessageUtil.sendMessage(player, "selectionClear")
            }
            event.setCanceled(true)
            return
        }

        val baseMaterialKey = config.settings.baseMaterial
        val blockState = world.getBlockState(pos)
        val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
        if (blockKey != baseMaterialKey) return

        NeoForgeSelectionManager.setSecondPoint(player, pos, worldKey)
        event.setCanceled(true)
    }
}
