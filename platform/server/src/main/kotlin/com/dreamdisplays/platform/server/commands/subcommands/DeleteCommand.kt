package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.api.ServerApiHost
import com.dreamdisplays.platform.client.utils.literalText
import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.mojang.brigadier.context.CommandContext
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
@PaperOnly
class DeleteCommand : SubCommand {
    override val name = "delete"
    override val permission: String? = null
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        val token = args.getOrNull(0) ?: "this"

        val data = resolvePaperDisplayTarget(sender, player, token) ?: return

        val isOwner = data.ownerId == player.uniqueId
        val canDelete = player.hasPermission(PaperServer.config.permissions.deleteOthers) ||
                (isOwner && player.hasPermission(PaperServer.config.permissions.delete))
        if (!canDelete) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }

        ServerApiHost.current?.askDelete(data, player.uniqueId)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return
        }

        DisplayManager.delete(data.id)
        ServerMessage.DISPLAY_DELETED.sendTo(player)
    }
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display delete` command. */
@ModLoaderOnly
object VanillaDeleteCommand {
    /** Deletes the display named by `this` (looked-at, within 32 blocks) or a remote id/prefix. */
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(literalText("Players only.")).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0

        val isOwner = data.ownerId == player.uuid
        val canDelete = VanillaPermissions.has(
            player,
            VanillaServerState.config.permissions.deleteOthers,
            VanillaPermissions.Fallback.OP
        ) || (isOwner && VanillaPermissions.has(
            player,
            VanillaServerState.config.permissions.delete,
            VanillaPermissions.Fallback.EVERYONE
        ))
        if (!canDelete) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return 0
        }

        ServerApiHost.current?.askDelete(data, player.uuid)?.let { reason ->
            if (reason.isNotEmpty()) MessageUtil.sendColoredMessage(player, reason)
            return 0
        }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        DisplayManager.delete(data)
        VanillaPacketUtil.sendDelete(receivers, data.id)
        ServerMessage.DISPLAY_DELETED.sendTo(player)
        return 1
    }
}
