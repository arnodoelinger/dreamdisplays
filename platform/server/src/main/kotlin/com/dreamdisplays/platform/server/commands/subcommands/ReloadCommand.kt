package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender

/**
 * Handles the `/display reload` command. Re-reads `config.yml` from disk at runtime
 * and confirms success or failure to the sender.
 */
@PaperOnly
class ReloadCommand : SubCommand {
    override val name = "reload"
    override val permission = PaperServer.config.permissions.reload

    /** Reloads `config.yml` from disk; replies with success or failure message. */
    override fun execute(sender: CommandSender, args: Array<String?>) =
        runCatching { PaperServer.config.reload() }.fold(
            onSuccess = {
                MessageUtil.sendMessage(sender, "configReloaded")
                MessageUtil.sendMessage(sender, "configReloadSummary")
            },
            onFailure = {
                MessageUtil.sendMessage(sender, "configReloadFailed")
            }
        )
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display reload` command.
 */
object VanillaReloadCommand {
    /** Reloads the server config from disk; replies with success or failure to the command source. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        runCatching { VanillaServerState.config.reload() }.fold(
            onSuccess = {
                player?.let {
                    MessageUtil.sendMessage(it, "configReloaded")
                    MessageUtil.sendMessage(it, "configReloadSummary")
                } ?: ctx.source.sendSystemMessage(Component.literal("Dream Displays config reloaded."))
            },
            onFailure = { e ->
                player?.let {
                    MessageUtil.sendMessage(it, "configReloadFailed")
                } ?: ctx.source.sendFailure(Component.literal("Failed to reload config: ${e.message}"))
            }
        )
        return 1
    }
}
