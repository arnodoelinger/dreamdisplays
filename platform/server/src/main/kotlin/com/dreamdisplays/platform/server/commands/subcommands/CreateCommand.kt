package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.client.utils.literalText
import com.dreamdisplays.shared.geometry.SurfaceCensus
import com.dreamdisplays.shared.geometry.SurfaceRole
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.platform.server.*
import com.dreamdisplays.platform.server.datatypes.selection.PaperSelectionData
import com.dreamdisplays.platform.server.datatypes.selection.VanillaSelectionData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.SelectionManager
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.WorldGuardRegions
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
//? if >=1.19.3 {
import net.minecraft.core.registries.BuiltInRegistries
//?} else
/*import net.minecraft.core.Registry as BuiltInRegistries*/
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.math.abs

/**
 * Handles the `/display create` command. Used for display creation after the player has made a selection with the wand.
 * Also validates the player's current selection, enforces overlap and Y-range limits, and
 * registers the resulting display.
 */
@PaperOnly
class CreateCommand : SubCommand {
    override val name = "create"
    override val permission = PaperServer.config.permissions.create
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val sel = SelectionManager.selectionPoints[player.uniqueId] as? PaperSelectionData
            ?: return MessageUtil.sendMessageWithMaterials(
                player, "noDisplayTerritories",
                PaperServer.config.settings.selectionMaterial, PaperServer.config.settings.baseMaterial
            )

        validate(
            sel,
            sendError = { key, args ->
                if (key == "noDisplayTerritories")
                    MessageUtil.sendMessageWithMaterials(
                        player,
                        key,
                        PaperServer.config.settings.selectionMaterial,
                        PaperServer.config.settings.baseMaterial
                    )
                else
                    MessageUtil.sendMessage(player, key, *args)
            },
            onWrongStructure = {
                MessageUtil.sendMessageWithMaterials(
                    player,
                    "wrongStructure",
                    PaperServer.config.settings.baseMaterial
                )
            }
        ) ?: return

        if (DisplayManager.isOverlaps(sel)) {
            ServerMessage.DISPLAY_OVERLAP.sendTo(player)
            return
        }

        val maxDisplays = PaperServer.config.settings.maxDisplaysPerPlayer
        if (maxDisplays > 0 && !player.hasPermission(PaperServer.config.permissions.createBypass) &&
            DisplayManager.countOwnedBy(player.uniqueId) >= maxDisplays
        ) {
            ServerMessage.DISPLAY_LIMIT_REACHED.sendTo(player, maxDisplays)
            return
        }

        val displayData = sel.generateDisplayData()
        val pos1 = sel.pos1
        val pos2 = sel.pos2
        if (pos1 != null && pos2 != null &&
            (WorldGuardRegions.isProtectedTerritory(pos1) || WorldGuardRegions.isProtectedTerritory(pos2))
        ) {
            displayData.access = DisplayAccess.REGION
        }
        ServerApiHost.current?.askCreate(displayData, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }
        SelectionManager.selectionPoints.remove(player.uniqueId)

        DisplayManager.register(displayData)
        ServerMessage.SUCCESSFUL_CREATION.sendTo(player)
    }

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

        val region = RegionUtil.calculateRegion(pos1, pos2)
        val face = sel.getFace()
        val (width, height, depth) = region.screenExtents(abs(face.modX), abs(face.modY), abs(face.modZ))
        val surface = PaperSurfaces.of(PaperServer.config.settings.baseMaterial)
        val world = pos1.world
        val census = if (world == null) {
            SurfaceCensus(foreign = 1)
        } else {
            var counted = SurfaceCensus()
            for (x in region.minX..region.maxX) {
                for (y in region.minY..region.maxY) {
                    for (z in region.minZ..region.maxZ) {
                        val type = world.getBlockAt(x, y, z).type
                        val role = if (type.isAir) SurfaceRole.AIR else surface.role(type.name)
                        counted = counted.add(role)
                    }
                }
            }
            counted
        }

        val conforming = validateRegion(
            minY = region.minY,
            maxY = region.maxY,
            depth = depth,
            width = width,
            height = height,
            census = census,
            minHeight = PaperServer.config.settings.minHeight,
            minWidth = PaperServer.config.settings.minWidth,
            maxHeight = PaperServer.config.settings.maxHeight,
            maxWidth = PaperServer.config.settings.maxWidth,
            sendError = sendError,
            onWrongStructure = onWrongStructure,
        ) ?: return null
        sel.conforming = conforming
        return sel
    }
}

/** Shared `Fabric` / `NeoForge` version of the [CreateCommand]. */
@ModLoaderOnly
object VanillaCreateCommand {
    /** Command execution logic. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(literalText("This command can only be used by a player.")).let { 0 }

        val sel = SelectionManager.selectionPoints[player.uuid] as? VanillaSelectionData
            ?: return MessageUtil.sendMessageWithMaterials(
                player,
                "noDisplayTerritories",
                VanillaServerState.config.settings.selectionMaterialId,
                VanillaServerState.config.settings.baseMaterialId
            ).let { 0 }

        validate(
            sel, ctx.source.server,
            sendError = { key, args ->
                if (key == "noDisplayTerritories")
                    MessageUtil.sendMessageWithMaterials(
                        player,
                        key,
                        VanillaServerState.config.settings.selectionMaterialId,
                        VanillaServerState.config.settings.baseMaterialId
                    )
                else
                    MessageUtil.sendMessage(player, key, *args)
            },
            onWrongStructure = {
                MessageUtil.sendMessageWithMaterials(
                    player,
                    "wrongStructure",
                    VanillaServerState.config.settings.baseMaterialId
                )
            }
        ) ?: return 0

        if (DisplayManager.isOverlaps(sel)) {
            ServerMessage.DISPLAY_OVERLAP.sendTo(player)
            return 0
        }

        val maxDisplays = VanillaServerState.config.settings.maxDisplaysPerPlayer
        if (maxDisplays > 0 &&
            !VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.createBypass,
                VanillaPermissions.Fallback.OP
            ) &&
            DisplayManager.countOwnedBy(player.uuid) >= maxDisplays
        ) {
            ServerMessage.DISPLAY_LIMIT_REACHED.sendTo(player, maxDisplays)
            return 0
        }

        val displayData = sel.generateDisplayData(player.uuid)
        ServerApiHost.current?.askCreate(displayData, player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return 0
        }
        SelectionManager.selectionPoints.remove(player.uuid)

        DisplayManager.register(displayData)
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(displayData) }

        val receivers = DisplayManager.getReceivers(displayData, ctx.source.server)
        VanillaPacketUtil.sendDisplayInfo(receivers, displayData)

        ServerMessage.SUCCESSFUL_CREATION.sendTo(player)
        return 1
    }

    private fun validate(
        sel: VanillaSelectionData,
        server: MinecraftServer,
        sendError: (String, Array<out Any>) -> Unit,
        onWrongStructure: (() -> Unit)? = null,
    ): VanillaSelectionData? {
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
        val (width, height, depth) = region.screenExtents(abs(facing.stepX), abs(facing.stepY), abs(facing.stepZ))
        val surface = VanillaSurfaces.of(VanillaServerState.config.settings.baseMaterialId)
        var census = SurfaceCensus()
        for (x in region.minX..region.maxX) {
            for (y in region.minY..region.maxY) {
                for (z in region.minZ..region.maxZ) {
                    val blockState = level.getBlockState(BlockPos(x, y, z))
                    val role = if (blockState.isAir) {
                        SurfaceRole.AIR
                    } else {
                        surface.role(BuiltInRegistries.BLOCK.getKey(blockState.block).toString())
                    }
                    census = census.add(role)
                }
            }
        }

        val conforming = validateRegion(
            minY = region.minY,
            maxY = region.maxY,
            depth = depth,
            width = width,
            height = height,
            census = census,
            minHeight = VanillaServerState.config.settings.minHeight,
            minWidth = VanillaServerState.config.settings.minWidth,
            maxHeight = VanillaServerState.config.settings.maxHeight,
            maxWidth = VanillaServerState.config.settings.maxWidth,
            sendError = sendError,
            onWrongStructure = onWrongStructure,
        ) ?: return null
        sel.conforming = conforming
        return sel
    }
}

private fun validateRegion(
    minY: Int,
    maxY: Int,
    depth: Int,
    width: Int,
    height: Int,
    census: SurfaceCensus,
    minHeight: Int,
    minWidth: Int,
    maxHeight: Int,
    maxWidth: Int,
    sendError: (String, Array<out Any>) -> Unit,
    onWrongStructure: (() -> Unit)? = null,
): Boolean? {
    if (census.conforming) {
        if (depth !in 1..maxOf(maxWidth, maxHeight)) {
            sendError("structureTooLarge", arrayOf(maxWidth, maxHeight))
            return null
        }
    } else if (!census.flat) {
        onWrongStructure?.invoke() ?: sendError("wrongStructure", emptyArray())
        return null
    } else if (depth != 1) {
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
    return census.conforming
}
