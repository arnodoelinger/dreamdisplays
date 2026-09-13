package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

/**
 * Handles the `/display name` command. Assigns a short, space-free alias to the display the
 * player is looking at (or a remote id), which [DisplayManager.resolveByIdOrPrefix] then accepts
 * anywhere a display id is accepted, e.g. `/display video <name> <url>`.
 */
@PaperOnly
class NameCommand : SubCommand {
    override val name = "name"
    override val permission = PaperServer.config.permissions.name
    override val playerOnly = true

    /** Renames the targeted display, after validating ownership and the name's shape/uniqueness. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        val token = args.getOrNull(0) ?: "this"

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return

        val requestedName = args.getOrNull(1)
        if (requestedName == null) {
            data.name?.let { MessageUtil.sendMessage(player, "currentName", it) }
                ?: MessageUtil.sendMessage(player, "noNameSet")
            return
        }

        if (data.ownerId != player.uniqueId &&
            !player.hasPermission(PaperServer.config.permissions.nameOthers)
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        val normalized = normalizeDisplayName(requestedName)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidName")
            return
        }
        if (DisplayManager.isNameTaken(normalized, data.id)) {
            MessageUtil.sendMessage(player, "nameTaken")
            return
        }

        data.name = normalized
        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        MessageUtil.sendMessage(player, "settedName")
    }
}

/** Canonical, storage-safe form of [raw], or null when it can't be used as a display alias. */
private val DISPLAY_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

private fun normalizeDisplayName(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (!DISPLAY_NAME_PATTERN.matches(trimmed)) return null
    if (trimmed.equals("this", ignoreCase = true)) return null
    if (runCatching { UUID.fromString(trimmed) }.isSuccess) return null
    return trimmed
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display name` command. */
@ModLoaderOnly
object VanillaNameCommand {
    /** Renames the targeted display, after validating ownership and the name's shape/uniqueness. */
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String, requestedName: String?): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0

        if (requestedName == null) {
            data.name?.let { MessageUtil.sendMessage(player, "currentName", it) }
                ?: MessageUtil.sendMessage(player, "noNameSet")
            return 1
        }

        if (data.ownerId != player.uuid &&
            !VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.nameOthers,
                VanillaPermissions.Fallback.OP,
            )
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return 0
        }

        val normalized = normalizeDisplayName(requestedName)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidName")
            return 0
        }
        if (DisplayManager.isNameTaken(normalized, data.id)) {
            MessageUtil.sendMessage(player, "nameTaken")
            return 0
        }

        data.name = normalized
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }
        MessageUtil.sendMessage(player, "settedName")
        return 1
    }
}
