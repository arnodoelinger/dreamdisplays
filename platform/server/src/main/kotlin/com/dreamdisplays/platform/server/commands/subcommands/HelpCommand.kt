package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.Server
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Prints the help message listing every `/display` subcommand.
 * Used for providing a quick reference to players for the available commands and their usage.
 */
@PaperOnly
class HelpCommand : SubCommand {
    override val name = "help"
    override val permission = Main.config.permissions.help
    override val playerOnly = true

    /** Prints the help message listing every `/display` subcommand. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        MessageUtil.sendColoredMessage(
            sender,
            $$"&7D |&f $${Main.config.getMessageForPlayer(player, "displayHelpHeader")}"
        )

        fun line(key: String) {
            MessageUtil.sendColoredMessage(
                sender, $$"&f $${Main.config.getMessageForPlayer(player, key)}"
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

/**
 * `Fabric`-specific implementation of the `/display help` command.
 */
@FabricOnly
object FabricHelpCommand {
    /** Prints the help message listing every `/display` subcommand. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        val config = Server.config

        /** Prints the localized message for [key] to the player, or to the command source if not a player. */
        fun line(key: String) {
            val msg = config.getMessageForPlayer(player, key)
            MessageUtil.sendColoredMessage(player ?: return, msg)
        }

        val header = config.getMessageForPlayer(player, "displayHelpHeader")
        MessageUtil.sendColoredMessage(player ?: run {
            ctx.source.sendSystemMessage(Component.literal("D | Help"))
            return 1
        }, header)

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
        return 1
    }
}
