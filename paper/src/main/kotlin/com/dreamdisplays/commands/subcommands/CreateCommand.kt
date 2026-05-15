package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main.Companion.config
import com.dreamdisplays.managers.DisplayManager
import com.dreamdisplays.managers.DisplayManager.register
import com.dreamdisplays.managers.DisplayValidator.isValidDisplay
import com.dreamdisplays.managers.DisplayValidator.sendErrorMessage
import com.dreamdisplays.managers.SelectionManager.selectionPoints
import com.dreamdisplays.utils.MessageUtil.sendMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class CreateCommand : SubCommand {

    override val name = "create"
    override val permission = config.permissions.create
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val sel = selectionPoints[player.uniqueId]
            ?: return sendMessage(player, "noDisplayTerritories")

        val valid = isValidDisplay(sel)
        if (valid != 6) {
            sendErrorMessage(player, valid)
            return
        }

        if (DisplayManager.isOverlaps(sel)) {
            sendMessage(player, "displayOverlap")
            return
        }

        // Prevent creating displays outside supported Y range (-2048..2047)
        val y1 = sel.pos1?.blockY ?: 0
        val y2 = sel.pos2?.blockY ?: 0
        val maxY = kotlin.math.max(y1, y2)
        val minY = kotlin.math.min(y1, y2)
        if (maxY > 2047) {
            sendMessage(player, "displayTooHigh")
            return
        }
        if (minY < -2048) {
            sendMessage(player, "displayTooLow")
            return
        }

        val displayData = sel.generateDisplayData()
        selectionPoints.remove(player.uniqueId)

        register(displayData)
        sendMessage(player, "successfulCreation")
    }
}
