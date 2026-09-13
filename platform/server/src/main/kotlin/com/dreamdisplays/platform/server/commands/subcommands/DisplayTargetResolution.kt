package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Resolves [token] to a [DisplayData] for `/display info|delete|video`, shared across all three since they all accept
 * the same `this` / id / name target syntax.
 */
@PaperOnly
fun resolvePaperDisplayTarget(sender: CommandSender, player: Player, token: String): DisplayData? {
    if (token.equals("this", ignoreCase = true)) {
        val block = player.getTargetBlock(null, 32)
        return DisplayManager.isContains(block.location) ?: run {
            MessageUtil.sendMessage(sender, "noDisplay")
            null
        }
    }
    if (!player.hasPermission(PaperServer.config.permissions.remote)) {
        MessageUtil.sendMessage(sender, "displayCommandMissingPermission")
        return null
    }
    return DisplayManager.resolveByIdOrPrefix(token) ?: run {
        MessageUtil.sendMessage(sender, "noDisplay")
        null
    }
}

/** `Fabric` / `NeoForge` counterpart of [resolvePaperDisplayTarget]. */
fun resolveVanillaDisplayTarget(player: ServerPlayer, token: String): DisplayData? {
    if (token.equals("this", ignoreCase = true)) {
        val targetPos = RegionUtil.getTargetedBlockPos(player) ?: run {
            MessageUtil.sendMessage(player, "noDisplay")
            return null
        }
        val worldKey = RegionUtil.getPlayerLevelKey(player)
        return DisplayManager.isContains(worldKey, targetPos) ?: run {
            MessageUtil.sendMessage(player, "noDisplay")
            null
        }
    }
    if (!VanillaPermissions.has(player, VanillaServerState.config.permissions.remote, VanillaPermissions.Fallback.OP)) {
        MessageUtil.sendMessage(player, "displayCommandMissingPermission")
        return null
    }
    return DisplayManager.resolveByIdOrPrefix(token) ?: run {
        MessageUtil.sendMessage(player, "noDisplay")
        null
    }
}
