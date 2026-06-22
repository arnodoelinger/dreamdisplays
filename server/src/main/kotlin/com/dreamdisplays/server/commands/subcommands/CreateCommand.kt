package com.dreamdisplays.server.commands.subcommands

import com.dreamdisplays.server.Main
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.datatypes.FabricSelectionData
import com.dreamdisplays.server.datatypes.PaperSelectionData
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.SelectionManager
import com.dreamdisplays.server.meta.ServerCoroutines
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.RegionUtil
import com.dreamdisplays.server.utils.net.FabricPacketUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.abs

/**
 * Handles the `/display create` command. Used for display creation after the player has made a selection with the wand.
 * Also validates the player's current selection, enforces overlap and Y-range limits, and
 * registers the resulting display.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly class CreateCommand : SubCommand {
    override val name = "create"
    override val permission = Main.config.permissions.create
    override val playerOnly = true

    /** Command execution logic. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val sel = SelectionManager.selectionPoints[player.uniqueId] as? PaperSelectionData
            ?: return MessageUtil.sendMessageWithMaterials(
                player, "noDisplayTerritories",
                Main.config.settings.selectionMaterial, Main.config.settings.baseMaterial
            )

        validate(sel,
            sendError = { key, args ->
                if (key == "noDisplayTerritories")
                    MessageUtil.sendMessageWithMaterials(player, key, Main.config.settings.selectionMaterial, Main.config.settings.baseMaterial)
                else
                    MessageUtil.sendMessage(player, key, *args)
            },
            onWrongStructure = { MessageUtil.sendMessageWithMaterials(player, "wrongStructure", Main.config.settings.baseMaterial) }
        ) ?: return

        if (DisplayManager.isOverlaps(sel)) {
            MessageUtil.sendMessage(player, "displayOverlap")
            return
        }

        val displayData = sel.generateDisplayData()
        SelectionManager.selectionPoints.remove(player.uniqueId)

        DisplayManager.register(displayData)
        MessageUtil.sendMessage(player, "successfulCreation")
    }

    /**
     * Validates the player's current selection, enforces overlap and Y-range limits, and
     * registers the resulting display.
     */
    private fun validate(
        sel: PaperSelectionData,
        sendError: (String, Array<out Any>) -> Unit,
        onWrongStructure: (() -> Unit)? = null,
    ): PaperSelectionData? {
        val pos1 = sel.pos1
        val pos2 = sel.pos2
        if (!sel.isReady || pos1 == null || pos2 == null) {
            sendError("noDisplayTerritories", emptyArray())
            return null
        }

        if (pos1.world != pos2.world) {
            sendError("secondPointNotSelected", emptyArray())
            return null
        }

        val minX = minOf(pos1.blockX, pos2.blockX)
        val maxX = maxOf(pos1.blockX, pos2.blockX)
        val minY = minOf(pos1.blockY, pos2.blockY)
        val maxY = maxOf(pos1.blockY, pos2.blockY)
        val minZ = minOf(pos1.blockZ, pos2.blockZ)
        val maxZ = maxOf(pos1.blockZ, pos2.blockZ)
        val deltaX = maxX - minX + 1
        val deltaZ = maxZ - minZ + 1
        val deltaY = maxY - minY + 1
        val face = sel.getFace()
        val isVertical = face == BlockFace.UP || face == BlockFace.DOWN
        val width = if (isVertical) deltaX else maxOf(deltaX, deltaZ)
        val height = if (isVertical) deltaZ else deltaY

        return validateRegion(
            minY = minY,
            maxY = maxY,
            deltaX = deltaX,
            deltaZ = deltaZ,
            deltaY = deltaY,
            faceModX = if (!isVertical) abs(face.modX) else 0,
            faceModZ = if (!isVertical) abs(face.modZ) else 0,
            faceModY = if (isVertical) abs(face.modY) else 0,
            width = width,
            height = height,
            minHeight = Main.config.settings.minHeight,
            minWidth = Main.config.settings.minWidth,
            maxHeight = Main.config.settings.maxHeight,
            maxWidth = Main.config.settings.maxWidth,
            hasExpectedBaseMaterial = {
                val world = pos1.world ?: return@validateRegion false
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        for (z in minZ..maxZ) {
                            if (world.getBlockAt(x, y, z).type != Main.config.settings.baseMaterial) {
                                return@validateRegion false
                            }
                        }
                    }
                }
                true
            },
            sendError = sendError,
            onWrongStructure = onWrongStructure,
        )?.let { sel }
    }
}

/** `Fabric`-specific version of the [CreateCommand]. */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@FabricOnly object FabricCreateCommand {
    /** Command execution logic. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("This command can only be used by a player.")).let { 0 }

        val sel = SelectionManager.selectionPoints[player.uuid] as? FabricSelectionData
            ?: return MessageUtil.sendMessageWithMaterials(
                player, "noDisplayTerritories",
                Server.config.settings.selectionMaterial, Server.config.settings.baseMaterial
            ).let { 0 }

        validate(sel, ctx.source.server,
            sendError = { key, args ->
                if (key == "noDisplayTerritories")
                    MessageUtil.sendMessageWithMaterials(player, key, Server.config.settings.selectionMaterial, Server.config.settings.baseMaterial)
                else
                    MessageUtil.sendMessage(player, key, *args)
            },
            onWrongStructure = { MessageUtil.sendMessageWithMaterials(player, "wrongStructure", Server.config.settings.baseMaterial) }
        ) ?: return 0

        if (DisplayManager.isOverlaps(sel)) {
            MessageUtil.sendMessage(player, "displayOverlap")
            return 0
        }

        val displayData = sel.generateDisplayData(player.uuid)
        SelectionManager.selectionPoints.remove(player.uuid)

        DisplayManager.register(displayData)
        ServerCoroutines.io.launch { Server.storage?.saveDisplay(displayData) }

        val receivers = DisplayManager.getReceivers(displayData, ctx.source.server)
        FabricPacketUtil.sendDisplayInfo(receivers, displayData)

        MessageUtil.sendMessage(player, "successfulCreation")
        return 1
    }

    /**
     * Validates the player's current selection, enforces overlap and Y-range limits, and
     * registers the resulting display.
     */
    private fun validate(
        sel: FabricSelectionData,
        server: MinecraftServer,
        sendError: (String, Array<out Any>) -> Unit,
        onWrongStructure: (() -> Unit)? = null,
    ): FabricSelectionData? {
        if (!sel.isReady || sel.pos1 == null || sel.pos2 == null) {
            sendError("noDisplayTerritories", emptyArray())
            return null
        }

        val region = sel.region() ?: run {
            sendError("noDisplayTerritories", emptyArray())
            return null
        }

        val worldKey = sel.worldKey ?: run {
            sendError("noDisplay", emptyArray())
            return null
        }
        val level = RegionUtil.getLevelByKey(server, worldKey) ?: run {
            sendError("noDisplay", emptyArray())
            return null
        }

        val facing = sel.facing
        val isVertical = facing == Direction.UP || facing == Direction.DOWN
        val faceModX = if (!isVertical && (facing == Direction.EAST || facing == Direction.WEST)) 1 else 0
        val faceModZ = if (!isVertical && (facing == Direction.NORTH || facing == Direction.SOUTH)) 1 else 0
        val faceModY = if (isVertical) 1 else 0
        val deltaY = region.maxY - region.minY + 1
        val screenWidth = if (isVertical) region.deltaX else region.width
        val screenHeight = if (isVertical) region.deltaZ else region.height

        return validateRegion(
            minY = region.minY,
            maxY = region.maxY,
            deltaX = region.deltaX,
            deltaZ = region.deltaZ,
            deltaY = deltaY,
            faceModX = faceModX,
            faceModZ = faceModZ,
            faceModY = faceModY,
            width = screenWidth,
            height = screenHeight,
            minHeight = Server.config.settings.minHeight,
            minWidth = Server.config.settings.minWidth,
            maxHeight = Server.config.settings.maxHeight,
            maxWidth = Server.config.settings.maxWidth,
            hasExpectedBaseMaterial = {
                for (x in region.minX..region.maxX) {
                    for (y in region.minY..region.maxY) {
                        for (z in region.minZ..region.maxZ) {
                            val blockState = level.getBlockState(BlockPos(x, y, z))
                            val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
                            if (blockKey != Server.config.settings.baseMaterial) {
                                return@validateRegion false
                            }
                        }
                    }
                }
                true
            },
            sendError = sendError,
            onWrongStructure = onWrongStructure,
        )?.let { sel }
    }
}

/** Validates the given region. */
private fun validateRegion(
    minY: Int,
    maxY: Int,
    deltaX: Int,
    deltaZ: Int,
    deltaY: Int,
    faceModX: Int,
    faceModZ: Int,
    faceModY: Int = 0,
    width: Int,
    height: Int,
    minHeight: Int,
    minWidth: Int,
    maxHeight: Int,
    maxWidth: Int,
    hasExpectedBaseMaterial: () -> Boolean,
    sendError: (String, Array<out Any>) -> Unit,
    onWrongStructure: (() -> Unit)? = null,
): Unit? {
    val depthOk = (faceModX != 0 && deltaX == faceModX)
        || (faceModZ != 0 && deltaZ == faceModZ)
        || (faceModY != 0 && deltaY == faceModY)
    if (!depthOk) {
        sendError("structureWrongDepth", emptyArray())
        return null
    }
    if (height < minHeight || width < minWidth) {
        sendError("structureTooSmall", arrayOf(minWidth, minHeight))
        return null
    }
    if (height > maxHeight || width > maxWidth) {
        sendError("structureTooLarge", arrayOf(maxWidth, maxHeight))
        return null
    }
    if (maxY > 2047) {
        sendError("displayTooHigh", emptyArray())
        return null
    }
    if (minY < -2048) {
        sendError("displayTooLow", emptyArray())
        return null
    }
    if (!hasExpectedBaseMaterial()) {
        onWrongStructure?.invoke() ?: sendError("wrongStructure", emptyArray())
        return null
    }
    return Unit
}
