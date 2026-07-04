package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.NeoForgeServer
import com.dreamdisplays.platform.server.Server
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
import org.bukkit.entity.Player

/**
 * Handles the `/display stats` command. Prints a per-mod-version breakdown of currently
 * connected players that have reported their client version.
 */
@PaperOnly
class StatsCommand : SubCommand {
    override val name = "stats"
    override val permission = Main.config.permissions.stats

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
            MessageUtil.sendColoredMessage(sender, format(sender, "displayStatsEntry", version, count))
        }

        val total = counts.values.sum()
        MessageUtil.sendColoredMessage(sender, format(sender, "displayStatsTotal", total))
    }

    /** Looks up the localized template for [key] and substitutes [values] via `String.format`. */
    private fun format(sender: CommandSender, key: String, vararg values: Any): String {
        val template = Main.config.getMessageForPlayer(sender as? Player, key) as? String ?: key
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }
}

/**
 * `Fabric`-specific implementation of the `/display stats` command.
 */
@FabricOnly
object FabricStatsCommand {
    /** Prints a per-mod-version breakdown of currently connected players that have reported their client version. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        val config = Server.config

        fun msg(key: String): String = config.getMessageForPlayer(player, key) as? String ?: key
        fun format(key: String, vararg values: Any): String {
            val template = msg(key)
            return runCatching { String.format(template, *values) }.getOrElse { template }
        }

        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        if (player != null) {
            MessageUtil.sendMessage(player, "displayStatsHeader")
            for ((version, count) in counts) {
                MessageUtil.sendColoredMessage(player, format("displayStatsEntry", version, count))
            }
            val total = counts.values.sum()
            MessageUtil.sendColoredMessage(player, format("displayStatsTotal", total))
        } else {
            ctx.source.sendSystemMessage(Component.literal(msg("displayStatsHeader")))
            for ((version, count) in counts) {
                ctx.source.sendSystemMessage(Component.literal(format("displayStatsEntry", version, count)))
            }
            val total = counts.values.sum()
            ctx.source.sendSystemMessage(Component.literal(format("displayStatsTotal", total)))
        }

        return 1
    }
}

/**
 * `NeoForge`-specific implementation of the `/display stats` command.
 */
@NeoForgeOnly
object NeoForgeStatsCommand {
    /** Prints a per-mod-version breakdown of currently connected players that have reported their client version. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        val config = NeoForgeServer.config

        fun msg(key: String): String = config.getMessageForPlayer(player, key) as? String ?: key
        fun format(key: String, vararg values: Any): String {
            val template = msg(key)
            return runCatching { String.format(template, *values) }.getOrElse { template }
        }

        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        if (player != null) {
            MessageUtil.sendMessage(player, "displayStatsHeader")
            for ((version, count) in counts) {
                MessageUtil.sendColoredMessage(player, format("displayStatsEntry", version, count))
            }
            val total = counts.values.sum()
            MessageUtil.sendColoredMessage(player, format("displayStatsTotal", total))
        } else {
            ctx.source.sendSystemMessage(Component.literal(msg("displayStatsHeader")))
            for ((version, count) in counts) {
                ctx.source.sendSystemMessage(Component.literal(format("displayStatsEntry", version, count)))
            }
            val total = counts.values.sum()
            ctx.source.sendSystemMessage(Component.literal(format("displayStatsTotal", total)))
        }

        return 1
    }
}
