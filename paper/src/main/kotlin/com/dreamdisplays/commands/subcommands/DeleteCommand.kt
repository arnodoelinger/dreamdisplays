package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.DisplayManager.delete
import com.dreamdisplays.managers.DisplayManager.isContains
import com.dreamdisplays.utils.MessageUtil.sendMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DeleteCommand : SubCommand {

    override val name = "delete"
    override val permission = config.permissions.delete
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val block = player.getTargetBlock(null, 32)
        if (block.type != config.settings.baseMaterial) {
            sendMessage(player, "noDisplay")
            return
        }

        val data = isContains(block.location)
            ?: return sendMessage(player, "noDisplay")

        delete(data)
        sendMessage(player, "displayDeleted")
    }
}
