package com.dreamdisplays.server.commands.subcommands

import com.dreamdisplays.server.Main
import com.dreamdisplays.server.Server
import com.dreamdisplays.server.utils.MessageUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender

/**
 * Handles the `/display reload` command. Re-reads `config.yml` from disk at runtime
 * and confirms success or failure to the sender.
 */
@PaperOnly class ReloadCommand : SubCommand {
    override val name = "reload"
    override val permission = Main.config.permissions.reload

    /** Reloads `config.yml` from disk; replies with success or failure message. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        try {
            Main.config.reload()
            MessageUtil.sendMessage(sender, "configReloaded")
            MessageUtil.sendMessage(sender, "configReloadSummary")
        } catch (_: Exception) {
            MessageUtil.sendMessage(sender, "configReloadFailed")
        }
    }
}

/**
 * `Fabric`-specific implementation of the `/display reload` command.
 */
@FabricOnly object FabricReloadCommand {
    /** Reloads the server config from disk; replies with success or failure to the command source. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer

        try {
            Server.config.reload()
            if (player != null) {
                MessageUtil.sendMessage(player, "configReloaded")
                MessageUtil.sendMessage(player, "configReloadSummary")
            } else {
                ctx.source.sendSystemMessage(Component.literal("Dream Displays config reloaded."))
            }
        } catch (e: Exception) {
            if (player != null) {
                MessageUtil.sendMessage(player, "configReloadFailed")
            } else {
                ctx.source.sendFailure(Component.literal("Failed to reload config: ${e.message}"))
            }
        }
        return 1
    }
}
