package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class HelpCommand : SubCommand {

    override val name = "help"
    override val permission = config.permissions.help
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        sendColoredMessage(sender, $$"&7D |&f $${config.getMessageForPlayer(player, "displayHelpHeader")}")

        fun line(key: String) {
            sendColoredMessage(
                sender, $$"&f $${config.getMessageForPlayer(player, key)}"
            )
        }

        line("displayHelpCreate")
        line("displayHelpVideo")
        line("displayHelpInfo")
        line("displayHelpDelete")
        line("displayHelpList")
        line("displayHelpStats")
        line("displayHelpReload")
        line("displayHelpOn")
        line("displayHelpOff")
        line("displayHelpHelp")
    }
}
