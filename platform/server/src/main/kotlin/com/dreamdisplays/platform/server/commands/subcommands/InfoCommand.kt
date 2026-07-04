package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display info` command. Prints owner, UUID, region, size, and media metadata
 * of the display the player is currently looking at.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class InfoCommand : SubCommand {
    override val name = "info"
    override val permission = PaperServer.config.permissions.info
    override val playerOnly = true

    /** Prints the owner, UUID, region, size and media metadata of the targeted display. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = sender as? Player ?: return

        val block = player.getTargetBlock(null, 32)
        val data = DisplayManager.isContains(block.location)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        val ownerName = Bukkit.getOfflinePlayer(data.ownerId).name ?: MessageUtil.messageFor(player, "displayInfoUnknownOwner")
        val worldName = data.pos1.world?.name ?: MessageUtil.messageFor(player, "displayInfoUnknownWorld")
        val displayUrl = data.url.ifBlank { MessageUtil.messageFor(player, "displayInfoUnavailableUrl") }
        val displayLang = data.lang.ifBlank { MessageUtil.messageFor(player, "displayInfoAutoLang") }
        val duration = data.duration?.toString() ?: MessageUtil.messageFor(player, "displayInfoUnknownDuration")

        MessageUtil.sendColoredMessage(player, MessageUtil.messageFor(player, "displayInfoHeader"))
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(player, "displayInfoOwnerLine", ownerName, data.ownerId.toString())
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(player, "displayInfoUuidLine", data.id.toString())
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(
                player,
                "displayInfoPositionLine",
                worldName,
                data.pos1.blockX.toString(),
                data.pos1.blockY.toString(),
                data.pos1.blockZ.toString(),
                data.pos2.blockX.toString(),
                data.pos2.blockY.toString(),
                data.pos2.blockZ.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(
                player,
                "displayInfoStateLine",
                data.width.toString(),
                data.height.toString(),
                data.facing.toString(),
                data.isSync.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(
                player,
                "displayInfoMediaLine",
                displayLang,
                duration,
                displayUrl
            )
        )
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display info` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
object VanillaInfoCommand {
    /** Prints owner, UUID, region, size, and media metadata of the targeted display. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val worldKey = RegionUtil.getPlayerLevelKey(player)
        val targetPos = RegionUtil.getTargetedBlockPos(player)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        val server = ctx.source.server

        val ownerName = server.playerList.players.find { it.uuid == data.ownerId }?.name?.string
            ?: MessageUtil.messageFor(player, "displayInfoUnknownOwner")
        val worldName = data.worldKey
        val displayUrl = data.url.ifBlank { MessageUtil.messageFor(player, "displayInfoUnavailableUrl") }
        val displayLang = data.lang.ifBlank { MessageUtil.messageFor(player, "displayInfoAutoLang") }
        val duration = data.duration?.toString() ?: MessageUtil.messageFor(player, "displayInfoUnknownDuration")

        MessageUtil.sendColoredMessage(player, MessageUtil.messageFor(player, "displayInfoHeader"))
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(player, "displayInfoOwnerLine", ownerName, data.ownerId.toString())
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(player, "displayInfoUuidLine", data.id.toString())
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(
                player,
                "displayInfoPositionLine",
                worldName,
                data.pos1.x.toString(), data.pos1.y.toString(), data.pos1.z.toString(),
                data.pos2.x.toString(), data.pos2.y.toString(), data.pos2.z.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(
                player,
                "displayInfoStateLine",
                data.width.toString(),
                data.height.toString(),
                data.facing.name,
                data.isSync.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            MessageUtil.formatIndexed(player, "displayInfoMediaLine", displayLang, duration, displayUrl)
        )
        return 1
    }
}
