package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main
import com.dreamdisplays.managers.PlayerManager.isDisplaysEnabled
import com.dreamdisplays.managers.PlayerManager.setDisplaysEnabled
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import com.dreamdisplays.utils.MessageUtil.sendMessage
import com.dreamdisplays.utils.net.PacketUtils.sendDisplayEnabled
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class OnCommand : SubCommand {

    override val name = "on"
    override val permission: String? = null
    override val playerOnly = false

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val target = resolveTarget(sender, args) ?: return
        val selfTarget = sender is Player && sender.uniqueId == target.uniqueId

        if (!selfTarget && !sender.hasPermission(Main.config.permissions.toggleOthers)) {
            sendMessage(sender, "displayCommandMissingPermission")
            return
        }

        if (isDisplaysEnabled(target)) {
            sendMessage(target, "display.already-enabled")
            if (!selfTarget) {
                sendColoredMessage(sender, format(sender, "display.already-enabled.target", target.name))
            }
            return
        }

        setDisplaysEnabled(target, true)
        sendDisplayEnabled(target, true)
        sendMessage(target, "display.enabled")
        if (!selfTarget) {
            sendColoredMessage(sender, format(sender, "display.enabled.target", target.name))
        }
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size != 2) return emptyList()
        if (!sender.hasPermission(Main.config.permissions.toggleOthers)) return emptyList()
        return Bukkit.getOnlinePlayers().map { it.name }.sorted()
    }

    private fun resolveTarget(sender: CommandSender, args: Array<String?>): Player? {
        if (args.size == 1) {
            return sender as? Player ?: run {
                sendMessage(sender, "displayWrongCommand")
                null
            }
        }
        if (args.size != 2) {
            sendMessage(sender, "displayWrongCommand")
            return null
        }

        val targetName = args[1]?.trim().orEmpty()
        if (targetName.isBlank()) {
            sendMessage(sender, "displayWrongCommand")
            return null
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target != null) return target

        sendColoredMessage(sender, format(sender, "displayTargetNotFound", targetName))
        return null
    }

    private fun format(sender: CommandSender, key: String, vararg values: Any): String {
        val template = Main.config.getMessageForPlayer(sender as? Player, key) as? String ?: key
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }
}
