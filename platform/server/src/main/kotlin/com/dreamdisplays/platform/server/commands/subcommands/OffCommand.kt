package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.Server
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.FabricPacketUtil
import com.dreamdisplays.platform.server.utils.net.PacketUtil
import com.dreamdisplays.platform.server.utils.net.ServerPacketHandler
import com.mojang.brigadier.context.CommandContext
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display off` command. Disables display rendering for the sender or for another
 * player when the caller holds the `toggleOthers` permission.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class OffCommand : SubCommand {
    override val name = "off"
    override val permission: String? = null
    override val playerOnly = false

    /** Disables displays for the sender, or for another player when permitted. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val target = resolveTarget(sender, args) ?: return
        val selfTarget = sender is Player && sender.uniqueId == target.uniqueId

        if (!selfTarget && !sender.hasPermission(Main.config.permissions.toggleOthers)) {
            MessageUtil.sendMessage(sender, "displayCommandMissingPermission")
            return
        }

        if (!PlayerManager.isDisplaysEnabled(target)) {
            MessageUtil.sendMessage(target, "display.already-disabled")
            if (!selfTarget) {
                MessageUtil.sendColoredMessage(sender, format(sender, "display.already-disabled.target", target.name))
            }
            return
        }

        PlayerManager.setDisplaysEnabled(target, false)
        PacketUtil.sendDisplayEnabled(target, false)
        MessageUtil.sendMessage(target, "display.disabled")
        if (!selfTarget) {
            MessageUtil.sendColoredMessage(sender, format(sender, "display.disabled.target", target.name))
        }
    }

    /** Suggests online player names when [sender] is allowed to toggle others. */
    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size != 2) return emptyList()
        if (!sender.hasPermission(Main.config.permissions.toggleOthers)) return emptyList()
        return Bukkit.getOnlinePlayers().map { it.name }.sorted()
    }

    /** Resolves the target player from [args], defaults to [sender] when no name was given. */
    private fun resolveTarget(sender: CommandSender, args: Array<String?>): Player? {
        if (args.size == 1) {
            return sender as? Player ?: run {
                MessageUtil.sendMessage(sender, "displayWrongCommand")
                null
            }
        }
        if (args.size != 2) {
            MessageUtil.sendMessage(sender, "displayWrongCommand")
            return null
        }

        val targetName = args[1]?.trim().orEmpty()
        if (targetName.isBlank()) {
            MessageUtil.sendMessage(sender, "displayWrongCommand")
            return null
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target != null) return target

        MessageUtil.sendColoredMessage(sender, format(sender, "displayTargetNotFound", targetName))
        return null
    }

    /** Looks up the localized template for [key] and substitutes [values] via `String.format`. */
    private fun format(sender: CommandSender, key: String, vararg values: Any): String {
        val template = Main.config.getMessageForPlayer(sender as? Player, key) as? String ?: key
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }
}

/**
 * `Fabric`-specific implementation of the `/display off` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@FabricOnly
object FabricOffCommand {
    /** Disables displays for the executing player or the named [targetName], checking op-level permission for the latter. */
    fun execute(ctx: CommandContext<CommandSourceStack>, targetName: String? = null): Int {
        val self = ctx.source.entity as? ServerPlayer
        val config = Server.config

        val target: ServerPlayer = if (targetName == null) {
            self ?: run {
                ctx.source.sendFailure(Component.literal("This command must be used in-game or with a player argument."))
                return 0
            }
        } else {
            ctx.source.server.playerList.getPlayerByName(targetName) ?: run {
                val msg = config.getMessageForPlayer(self, "displayTargetNotFound") as? String ?: "Player not found: %s"
                if (self != null) MessageUtil.sendColoredMessage(self, String.format(msg, targetName))
                else ctx.source.sendFailure(Component.literal(String.format(msg, targetName)))
                return 0
            }
        }

        val selfTarget = self?.uuid == target.uuid

        if (!selfTarget && (self == null || !ServerPacketHandler.isOpLevel2(self))) {
            if (self != null) MessageUtil.sendMessage(self, "displayCommandMissingPermission")
            else ctx.source.sendFailure(Component.literal("Missing permission."))
            return 0
        }

        if (!PlayerManager.isDisplaysEnabled(target)) {
            MessageUtil.sendMessage(target, "display.already-disabled")
            if (!selfTarget) {
                val msg = config.getMessageForPlayer(self, "display.already-disabled.target") as? String
                if (msg != null) MessageUtil.sendColoredMessage(self, String.format(msg, target.name.string))
            }
            return 1
        }

        PlayerManager.setDisplaysEnabled(target, false)
        FabricPacketUtil.sendDisplayEnabled(target, false)
        MessageUtil.sendMessage(target, "display.disabled")
        if (!selfTarget) {
            val msg = config.getMessageForPlayer(self, "display.disabled.target") as? String
            if (msg != null) MessageUtil.sendColoredMessage(self, String.format(msg, target.name.string))
        }
        return 1
    }
}
