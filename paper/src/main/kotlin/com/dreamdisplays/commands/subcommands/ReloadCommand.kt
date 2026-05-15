package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.utils.MessageUtil.sendMessage
import org.bukkit.command.CommandSender

class ReloadCommand : SubCommand {

    override val name = "reload"
    override val permission = config.permissions.reload

    override fun execute(sender: CommandSender, args: Array<String?>) {
        try {
            config.reload()
            sendMessage(sender, "configReloaded")
            sendMessage(sender, "configReloadSummary")
        } catch (_: Exception) {
            sendMessage(sender, "configReloadFailed")
        }
    }
}
