package com.dreamdisplays.server.commands.subcommands

import com.dreamdisplays.protocol.PlaybackPermissions
import com.dreamdisplays.server.Main
import com.dreamdisplays.server.managers.DisplayManager
import com.dreamdisplays.server.managers.StateManager
import com.dreamdisplays.server.playback.PlaybackContexts
import com.dreamdisplays.server.playback.TimelineManager
import com.dreamdisplays.server.utils.MessageUtil
import com.dreamdisplays.server.utils.RegionUtil
import com.dreamdisplays.server.utils.YouTubeUtil
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
import java.util.*

/**
 * Handles the `/display video` command. Assigns a YouTube URL (and optional audio language)
 * to the display the player is looking at, after validating ownership.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly class VideoCommand : SubCommand {
    override val name = "video"
    override val permission = Main.config.permissions.video
    override val playerOnly = true

    /**
     * Assigns a YouTube URL (and optional language) to the targeted display owned by the player,
     * rebroadcasting the new state and resetting sync playback when the display was synced.
     */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        if (args.size < 2) {
            MessageUtil.sendMessage(player, "invalidURL")
            return
        }

        val code = YouTubeUtil.extractVideoIdFromUri(args[1] ?: "")
            ?: return MessageUtil.sendMessage(player, "invalidURL")

        val block = player.getTargetBlock(null, 32)

        if (block.type != Main.config.settings.baseMaterial) {
            MessageUtil.sendMessage(player, "displayVideoWrongTargetBlock")
            return
        }

        val data = DisplayManager.isContains(block.location)
        if (data == null) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(Main.config.permissions.delete))
            )
        ) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return
        }

        val wasSync = data.isSync
        data.apply {
            url = if ("/shorts/" in (args[1] ?: "")) "https://www.youtube.com/shorts/$code"
                  else "https://www.youtube.com/watch?v=$code"
            lang = normalizeLangCode(args.getOrNull(2).orEmpty())
        }

        val receivers = DisplayManager.getReceivers(data)
        DisplayManager.sendUpdate(data, receivers)
        if (wasSync) StateManager.resetAndBroadcast(data.id, receivers)
        TimelineManager.onVideoChanged(data)

        MessageUtil.sendMessage(player, "settedURL")
    }

    /** Suggests known two-letter language codes when completing the third argument. */
    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size == 3) {
            return languageSuggestions
        }
        return emptyList()
    }

    /** Lowercases [raw], trims region suffixes and maps the `ua` alias to the canonical `uk`. */
    private fun normalizeLangCode(raw: String): String {
        val base = raw.trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .substringBefore('_')

        return when (base) {
            "ua" -> "uk"
            else -> base
        }
    }

    companion object {
        val languageSuggestions: List<String> by lazy {
            val fromJavaLocales = Locale.getAvailableLocales()
                .asSequence()
                .map { it.language.lowercase(Locale.ROOT) }

            val fromPlugin = Main.config.languages.keys
                .asSequence()
                .map { it.trim().lowercase(Locale.ROOT).replace('-', '_').substringBefore('_') }

            return@lazy (fromJavaLocales + fromPlugin)
                .filter { it.matches(Regex("^[a-z]{2}$")) }
                .map { code ->
                    if (code == "uk") "ua" else code
                }
                .distinct()
                .sorted()
                .toList()
        }
    }
}

/**
 * `Fabric`-specific implementation of the `/display video` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@FabricOnly object FabricVideoCommand {
    /** Assigns a YouTube URL (and optional language) to the targeted display, after validating ownership. */
    fun execute(ctx: CommandContext<CommandSourceStack>, urlAndLang: String): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val parts = urlAndLang.trim().split(" ")
        val urlRaw = parts[0]
        val langRaw = if (parts.size > 1) parts.last() else ""

        if (urlRaw.isBlank()) {
            MessageUtil.sendMessage(player, "invalidURL")
            return 0
        }

        val code = YouTubeUtil.extractVideoIdFromUri(urlRaw)
            ?: return MessageUtil.sendMessage(player, "invalidURL").let { 0 }

        val targetPos = getTargetBlockPos(player)
            ?: return MessageUtil.sendMessage(player, "displayVideoWrongTargetBlock").let { 0 }

        val worldKey = RegionUtil.getLevelKey(player.level())
        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uuid, ServerPacketHandler.isOpLevel2(player))
            )
        ) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return 0
        }

        val wasSync = data.isSync
        data.url = if ("/shorts/" in urlRaw) "https://www.youtube.com/shorts/$code"
                   else "https://www.youtube.com/watch?v=$code"
        data.lang = normalizeLangCode(langRaw)

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        FabricPacketUtil.sendDisplayInfo(receivers, data)
        if (wasSync) StateManager.resetAndBroadcast(data.id, receivers)
        TimelineManager.onVideoChanged(data)

        MessageUtil.sendMessage(player, "settedURL")
        return 1
    }

    /** Lowercases [raw], trims region suffixes, and maps the `ua` alias to the canonical `uk`. */
    private fun normalizeLangCode(raw: String): String {
        return when (val base = raw.trim().lowercase(Locale.ROOT).replace('-', '_').substringBefore('_')) {
            "ua" -> "uk"
            else -> base
        }
    }

    /** Gets the block position the player is currently looking at (within 32 blocks). */
    private fun getTargetBlockPos(player: ServerPlayer): BlockPos? {
        val level = player.level()
        val start = player.eyePosition
        val end = start.add(player.lookAngle.scale(32.0))
        val hit = level.clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return if (hit.type == HitResult.Type.BLOCK) hit.blockPos else null
    }
}
