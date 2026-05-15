package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.PlayerManager.getVersions
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import com.dreamdisplays.utils.MessageUtil.sendMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class StatsCommand : SubCommand {

    override val name = "stats"
    override val permission = config.permissions.stats

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val versions = getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        sendMessage(sender, "displayStatsHeader")

        for ((version, count) in counts) {
            sendColoredMessage(sender, format(sender, "displayStatsEntry", version, count))
        }

        val total = counts.values.sum()
        sendColoredMessage(sender, format(sender, "displayStatsTotal", total))
    }

    private fun format(sender: CommandSender, key: String, vararg values: Any): String {
        val template = config.getMessageForPlayer(sender as? Player, key) as? String ?: key
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }
}
