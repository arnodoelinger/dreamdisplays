package com.dreamdisplays.server.commands.subcommands

import com.dreamdisplays.server.Main
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.RegionUtil
import com.dreamdisplays.server.utils.net.FabricPacketUtil
import com.dreamdisplays.server.utils.net.ServerPacketHandler
import com.mojang.brigadier.context.CommandContext
import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display delete` command. Used for deleting displays the player is currently looking at.
 * Used for admin-only deletion of displays.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly class DeleteCommand : SubCommand {
    override val name = "delete"
    override val permission = Main.config.permissions.delete
    override val playerOnly = true

    /** Deletes the display the player is currently looking at (within 32 blocks). */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return

        val block = player.getTargetBlock(null, 32)
        if (block.type != Main.config.settings.baseMaterial) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        val data = DisplayManager.isContains(block.location)
            ?: return MessageUtil.sendMessage(player, "noDisplay")

        DisplayManager.delete(data)
        MessageUtil.sendMessage(player, "displayDeleted")
    }
}

/**
 * `Fabric`-specific implementation of the `/display delete` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@FabricOnly object FabricDeleteCommand {
    /** Deletes the display the player is currently looking at (within 32 blocks). */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val targetPos = getTargetBlockPos(player) ?: run {
            MessageUtil.sendMessage(player, "noDisplay")
            return 0
        }

        val worldKey = RegionUtil.getLevelKey(player.level())

        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        if (data.ownerId != player.uuid && !ServerPacketHandler.isOpLevel2(player)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return 0
        }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        DisplayManager.delete(data)
        FabricPacketUtil.sendDelete(receivers, data.id)
        MessageUtil.sendMessage(player, "displayDeleted")
        return 1
    }

    /** Gets the block position the player is currently looking at. */
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
