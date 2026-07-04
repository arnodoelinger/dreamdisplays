package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.Main
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display info` command. Prints owner, UUID, region, size, and media metadata
 * of the display the player is currently looking at.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class InfoCommand : SubCommand {
    override val name = "info"
    override val permission = Main.config.permissions.info
    override val playerOnly = true

    /** Prints the owner, UUID, region, size and media metadata of the targeted display. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = sender as? Player ?: return

        val block = player.getTargetBlock(null, 32)
        val data = DisplayManager.isContains(block.location)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        val ownerName = Bukkit.getOfflinePlayer(data.ownerId).name ?: text(player, "displayInfoUnknownOwner")
        val worldName = data.pos1.world?.name ?: text(player, "displayInfoUnknownWorld")
        val displayUrl = data.url.ifBlank { text(player, "displayInfoUnavailableUrl") }
        val displayLang = data.lang.ifBlank { text(player, "displayInfoAutoLang") }
        val duration = data.duration?.toString() ?: text(player, "displayInfoUnknownDuration")

        MessageUtil.sendColoredMessage(player, text(player, "displayInfoHeader"))
        MessageUtil.sendColoredMessage(
            player,
            format(
                player,
                "displayInfoOwnerLine",
                ownerName,
                data.ownerId.toString()
            )
        )
        MessageUtil.sendColoredMessage(player, format(player, "displayInfoUuidLine", data.id.toString()))
        MessageUtil.sendColoredMessage(
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
        MessageUtil.sendColoredMessage(
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
        MessageUtil.sendColoredMessage(
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

    /** Returns the localized message for [key] in [player]'s language, or [key] as fallback. */
    private fun text(player: Player, key: String): String {
        return Main.config.getMessageForPlayer(player, key) as? String ?: key
    }

    /** Resolves the localized template for [key] and substitutes positional [args] into it. */
    private fun format(player: Player, key: String, vararg args: String): String {
        return applyPlaceholders(text(player, key), *args)
    }

    /** Replaces `{0}`, `{1}`, ... placeholders in [template] with the matching value from [values]. */
    private fun applyPlaceholders(template: String, vararg values: String): String {
        var result = template
        values.forEachIndexed { index, value ->
            result = result.replace("{$index}", value)
        }
        return result
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display info` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
object VanillaInfoCommand {
    /** Prints owner, UUID, region, size, and media metadata of the targeted display. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val worldKey = RegionUtil.getPlayerLevelKey(player)
        val targetPos = getTargetBlockPos(player)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        val config = VanillaServerState.config
        val server = ctx.source.server

        fun text(key: String): String =
            config.getMessageForPlayer(player, key) as? String ?: key

        fun format(key: String, vararg args: String): String {
            val template = text(key)
            var result = template
            args.forEachIndexed { index, value -> result = result.replace("{$index}", value) }
            return result
        }

        val ownerName =
            server.playerList.players.find { it.uuid == data.ownerId }?.name?.string ?: text("displayInfoUnknownOwner")
        val worldName = data.worldKey
        val displayUrl = data.url.ifBlank { text("displayInfoUnavailableUrl") }
        val displayLang = data.lang.ifBlank { text("displayInfoAutoLang") }
        val duration = data.duration?.toString() ?: text("displayInfoUnknownDuration")

        MessageUtil.sendColoredMessage(player, text("displayInfoHeader"))
        MessageUtil.sendColoredMessage(player, format("displayInfoOwnerLine", ownerName, data.ownerId.toString()))
        MessageUtil.sendColoredMessage(player, format("displayInfoUuidLine", data.id.toString()))
        MessageUtil.sendColoredMessage(
            player,
            format(
                "displayInfoPositionLine",
                worldName,
                data.pos1.x.toString(), data.pos1.y.toString(), data.pos1.z.toString(),
                data.pos2.x.toString(), data.pos2.y.toString(), data.pos2.z.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            format(
                "displayInfoStateLine",
                data.width.toString(),
                data.height.toString(),
                data.facing.name,
                data.isSync.toString()
            )
        )
        MessageUtil.sendColoredMessage(
            player,
            format("displayInfoMediaLine", displayLang, duration, displayUrl)
        )
        return 1
    }

    /** Gets the block position the player is currently looking at (within 32 blocks). */
    private fun getTargetBlockPos(player: ServerPlayer): BlockPos? {
        val level = player.level()
        val eyePos = player.eyePosition
        val lookVec = player.lookAngle
        val hit = level.clip(
            ClipContext(
                eyePos,
                eyePos.add(lookVec.scale(32.0)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
        )
        return if (hit.type == HitResult.Type.BLOCK) hit.blockPos else null
    }
}
