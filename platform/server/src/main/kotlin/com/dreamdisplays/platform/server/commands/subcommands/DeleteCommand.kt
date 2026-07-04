package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.dreamdisplays.platform.server.utils.net.VanillaServerPacketHandler
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
 * Used for admin-only deletion of displays.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class DeleteCommand : SubCommand {
    override val name = "delete"
    override val permission = Main.config.permissions.delete
    override val playerOnly = true

    /** Deletes the display the player is currently looking at (within 32 blocks). */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val block = player.getTargetBlock(null, 32)
        if (block.type != Main.config.settings.baseMaterial) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        val data = DisplayManager.isContains(block.location)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

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

        if (data.ownerId != player.uuid && !VanillaServerPacketHandler.isOpLevel2(player)) {
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
