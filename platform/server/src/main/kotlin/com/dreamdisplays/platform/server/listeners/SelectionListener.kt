package com.dreamdisplays.platform.server.listeners

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.managers.SelectionVisualizer
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
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
 * Shared `Fabric` / `NeoForge` selection-point handling. [FabricSelectionListener] and
 * [NeoForgeSelectionListener] only adapt their loader's event API and hand off here.
 */
object VanillaSelectionListener {
    /** Left-click: sets pos1 if holding the selection tool over the base material. Returns true if handled. */
    fun handleLeftClick(player: ServerPlayer, world: ServerLevel, pos: BlockPos): Boolean {
        val config = VanillaServerState.config
        val selMaterialKey = config.settings.selectionMaterial
        val heldItem = player.mainHandItem
        val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
        if (heldItemKey != selMaterialKey) return false

        val baseMaterialKey = config.settings.baseMaterial
        val blockState = world.getBlockState(pos)
        val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
        if (blockKey != baseMaterialKey) return false

        val worldKey = RegionUtil.getLevelKey(world)
        val face = Direction.orderedByNearest(player)[0].opposite
        SelectionManager.setFirstPoint(player, pos, worldKey, face)
        return true
    }

    /** Right-click: sets pos2, or resets when sneaking. Returns true if handled. */
    fun handleRightClick(player: ServerPlayer, world: ServerLevel, pos: BlockPos): Boolean {
        val config = VanillaServerState.config
        val selMaterialKey = config.settings.selectionMaterial
        val heldItem = player.mainHandItem
        val heldItemKey = BuiltInRegistries.ITEM.getKey(heldItem.item).toString()
        if (heldItemKey != selMaterialKey) return false

        val worldKey = RegionUtil.getLevelKey(world)

        if (player.isShiftKeyDown) {
            if (SelectionManager.selectionPoints.containsKey(player.uuid)) {
                SelectionManager.resetSelection(player)
                MessageUtil.sendMessage(player, "selectionClear")
            }
            return true
        }

        val baseMaterialKey = config.settings.baseMaterial
        val blockState = world.getBlockState(pos)
        val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
        if (blockKey != baseMaterialKey) return false

        SelectionManager.setSecondPoint(player, pos, worldKey)
        return true
    }
}

/** `Fabric` event adapter for [VanillaSelectionListener]. */
@FabricOnly
object FabricSelectionListener {
    /** Registers the selection listener. */
    fun register() {
        AttackBlockCallback.EVENT.register { player, world, hand, pos, _ ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            if (player !is ServerPlayer) return@register InteractionResult.PASS
            if (world !is ServerLevel) return@register InteractionResult.PASS

            if (VanillaSelectionListener.handleLeftClick(player, world, pos)) InteractionResult.SUCCESS
            else InteractionResult.PASS
        }

        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            if (player !is ServerPlayer) return@register InteractionResult.PASS
            if (world !is ServerLevel) return@register InteractionResult.PASS

            if (VanillaSelectionListener.handleRightClick(player, world, hitResult.blockPos)) InteractionResult.SUCCESS
            else InteractionResult.PASS
        }
    }
}

/** `NeoForge` event adapter for [VanillaSelectionListener]. */
@NeoForgeOnly
object NeoForgeSelectionListener {
    /** Left-click sets pos1. */
    @SubscribeEvent
    fun onLeftClick(event: NeoForgePlayerInteractEvent.LeftClickBlock) {
        if (event.action != NeoForgePlayerInteractEvent.LeftClickBlock.Action.START) return
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return

        if (VanillaSelectionListener.handleLeftClick(player, world, event.pos)) event.setCanceled(true)
    }

    /** Right-click sets pos2; sneak + right-click resets. */
    @SubscribeEvent
    fun onRightClick(event: NeoForgePlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val world = event.level as? ServerLevel ?: return

        if (VanillaSelectionListener.handleRightClick(player, world, event.pos)) event.setCanceled(true)
    }
}
