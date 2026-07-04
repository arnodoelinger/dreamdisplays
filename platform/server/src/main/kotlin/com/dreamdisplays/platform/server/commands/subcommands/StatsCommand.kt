package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender

/**
 * Handles the `/display stats` command. Prints a per-mod-version breakdown of currently
 * connected players that have reported their client version.
 */
@PaperOnly
class StatsCommand : SubCommand {
    override val name = "stats"
    override val permission = PaperServer.config.permissions.stats

    /** Prints a per-mod-version count of currently connected players that have reported a version. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        MessageUtil.sendMessage(sender, "displayStatsHeader")

        for ((version, count) in counts) {
            MessageUtil.sendColoredMessage(sender, MessageUtil.formatPrintf(sender, "displayStatsEntry", version, count))
        }

        val total = counts.values.sum()
        MessageUtil.sendColoredMessage(sender, MessageUtil.formatPrintf(sender, "displayStatsTotal", total))
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display stats` command.
 */
object VanillaStatsCommand {
    /** Prints a per-mod-version breakdown of currently connected players that have reported their client version. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer

        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        if (player != null) {
            MessageUtil.sendMessage(player, "displayStatsHeader")
            for ((version, count) in counts) {
                MessageUtil.sendColoredMessage(player, MessageUtil.formatPrintf(player, "displayStatsEntry", version, count))
            }
            val total = counts.values.sum()
            MessageUtil.sendColoredMessage(player, MessageUtil.formatPrintf(player, "displayStatsTotal", total))
        } else {
            ctx.source.sendSystemMessage(Component.literal(MessageUtil.messageFor(player, "displayStatsHeader")))
            for ((version, count) in counts) {
                ctx.source.sendSystemMessage(Component.literal(MessageUtil.formatPrintf(player, "displayStatsEntry", version, count)))
            }
            val total = counts.values.sum()
            ctx.source.sendSystemMessage(Component.literal(MessageUtil.formatPrintf(player, "displayStatsTotal", total)))
        }

        return 1
    }
}
