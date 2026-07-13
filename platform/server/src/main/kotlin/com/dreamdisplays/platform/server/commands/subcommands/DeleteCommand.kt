package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.baseMaterial
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display delete` command. Used for deleting displays the player is currently looking at.
 * Players can delete their own displays; deleting others' requires the `delete.others` permission.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class DeleteCommand : SubCommand {
    override val name = "delete"
    override val permission: String? = null
    override val playerOnly = true

    /** Deletes the display the player is currently looking at (within 32 blocks). */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val block = player.getTargetBlock(null, 32)
        if (block.type != PaperServer.config.settings.baseMaterial) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        val data = DisplayManager.isContains(block.location)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        if (data.ownerId != player.uniqueId &&
            !player.hasPermission(PaperServer.config.permissions.deleteOthers)
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        DisplayManager.delete(data)
        MessageUtil.sendMessage(player, "displayDeleted")
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display delete` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
object VanillaDeleteCommand {
    /** Deletes the display the player is currently looking at (within 32 blocks). */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val targetPos = RegionUtil.getTargetedBlockPos(player) ?: run {
            MessageUtil.sendMessage(player, "noDisplay")
            return 0
        }

        val worldKey = RegionUtil.getPlayerLevelKey(player)

        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        if (data.ownerId != player.uuid &&
            !VanillaPermissions.has(player, VanillaServerState.config.permissions.deleteOthers, VanillaPermissions.Fallback.OP)
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return 0
        }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        DisplayManager.delete(data)
        VanillaPacketUtil.sendDelete(receivers, data.id)
        MessageUtil.sendMessage(player, "displayDeleted")
        return 1
    }
}
