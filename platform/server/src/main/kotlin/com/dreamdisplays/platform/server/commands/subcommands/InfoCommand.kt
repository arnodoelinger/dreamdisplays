package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display info` command. Prints owner, UUID, region, size, and media metadata
 * of the display named by `this` (looked-at) or a remote id/prefix.
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
        val token = args.getOrNull(0) ?: "this"

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return

        val ownerName =
            Bukkit.getOfflinePlayer(data.ownerId).name ?: MessageUtil.messageFor(player, "displayInfoUnknownOwner")
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
        data.scheduledStart?.let { at ->
            val localSecond = ScheduleTimeUtil.localSecondOfDay(at, ScheduleTimeUtil.offsetMinutesOf(player.uniqueId))
            val actionKey =
                if (data.scheduledAction == PlaybackAction.PAUSE) "scheduleActionPause" else "scheduleActionPlay"
            MessageUtil.sendColoredMessage(
                player,
                MessageUtil.formatIndexed(
                    player,
                    "displayInfoScheduleLine",
                    MessageUtil.messageFor(player, actionKey),
                    ScheduleTimeUtil.format(localSecond, withSeconds = true),
                )
            )
        }
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display info` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@ModLoaderOnly
object VanillaInfoCommand {
    /** Prints owner, UUID, region, size, and media metadata of the targeted display. */
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0

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
        data.scheduledStart?.let { at ->
            val localSecond = ScheduleTimeUtil.localSecondOfDay(at, ScheduleTimeUtil.offsetMinutesOf(player.uuid))
            val actionKey =
                if (data.scheduledAction == PlaybackAction.PAUSE) "scheduleActionPause" else "scheduleActionPlay"
            MessageUtil.sendColoredMessage(
                player,
                MessageUtil.formatIndexed(
                    player,
                    "displayInfoScheduleLine",
                    MessageUtil.messageFor(player, actionKey),
                    ScheduleTimeUtil.format(localSecond, withSeconds = true),
                )
            )
        }
        return 1
    }
}
