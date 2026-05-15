package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main
import com.dreamdisplays.managers.DisplayManager.isContains
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import com.dreamdisplays.utils.MessageUtil.sendMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class InfoCommand : SubCommand {
    override val name = "info"
    override val permission = Main.config.permissions.info
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = sender as? Player ?: return

        val block = player.getTargetBlock(null, 32)
        val data = isContains(block.location)
            ?: return sendMessage(player, "noDisplay")

        val ownerName = Bukkit.getOfflinePlayer(data.ownerId).name ?: text(player, "displayInfoUnknownOwner")
        val worldName = data.pos1.world?.name ?: text(player, "displayInfoUnknownWorld")
        val displayUrl = data.url.ifBlank { text(player, "displayInfoUnavailableUrl") }
        val displayLang = data.lang.ifBlank { text(player, "displayInfoAutoLang") }
        val duration = data.duration?.toString() ?: text(player, "displayInfoUnknownDuration")

        sendColoredMessage(player, text(player, "displayInfoHeader"))
        sendColoredMessage(
            player,
            format(
                player,
                "displayInfoOwnerLine",
                ownerName,
                data.ownerId.toString()
            )
        )
        sendColoredMessage(player, format(player, "displayInfoUuidLine", data.id.toString()))
        sendColoredMessage(
            player,
            format(
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
        sendColoredMessage(
            player,
            format(
                player,
                "displayInfoStateLine",
                data.width.toString(),
                data.height.toString(),
                data.facing.toString(),
                data.isSync.toString()
            )
        )
        sendColoredMessage(
            player,
            format(
                player,
                "displayInfoMediaLine",
                displayLang,
                duration,
                displayUrl
            )
        )
    }

    private fun text(player: Player, key: String): String {
        return Main.config.getMessageForPlayer(player, key) as? String ?: key
    }

    private fun format(player: Player, key: String, vararg args: String): String {
        return applyPlaceholders(text(player, key), *args)
    }

    private fun applyPlaceholders(template: String, vararg values: String): String {
        var result = template
        values.forEachIndexed { index, value ->
            result = result.replace("{$index}", value)
        }
        return result
    }
}
