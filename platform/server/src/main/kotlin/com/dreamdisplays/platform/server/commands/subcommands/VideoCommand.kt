package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.media.search.YouTubeUrls
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.api.security.LanguageTag
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.baseMaterial
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.meta.ServerCoroutines
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
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
 * Handles the `/display video` command. Assigns a YouTube URL (and optional audio language)
 * to the display the player is looking at, after validating ownership.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
@PaperOnly
class VideoCommand : SubCommand {
    override val name = "video"
    override val permission = PaperServer.config.permissions.video
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

        val videoId = YouTubeUrls.extractVideoIdTyped(args[1] ?: "")
        if (videoId == null) {
            MessageUtil.sendMessage(player, "invalidURL")
            return
        }

        val block = player.getTargetBlock(null, 32)

        if (block.type != PaperServer.config.settings.baseMaterial) {
            MessageUtil.sendMessage(player, "displayVideoWrongTargetBlock")
            return
        }

        val data = DisplayManager.isContains(block.location)
        if (data == null) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))
            )
        ) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return
        }

        val wasSync = data.isSync
        data.apply {
            url = YouTubeUrls.watchUrl(videoId)
            lang = LanguageTag.canonicalAudioCode(args.getOrNull(2)).value
        }

        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        if (wasSync) StateManager.resetAndBroadcast(data)
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

    companion object {
        val languageSuggestions: List<String> by lazy {
            val fromJavaLocales = Locale.getAvailableLocales()
                .asSequence()
                .map { it.language.lowercase(Locale.ROOT) }

            val fromPlugin = PaperServer.config.languages.keys
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
 * Shared `Fabric` / `NeoForge` implementation of the `/display video` command.
 */
@Deprecated("This command is being replaced by UI interface. Will be removed in a future update.")
object VanillaVideoCommand {
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

        val videoId = YouTubeUrls.extractVideoIdTyped(urlRaw)
            ?: return MessageUtil.sendMessage(player, "invalidURL").let { 0 }

        val targetPos = RegionUtil.getTargetedBlockPos(player)
            ?: return MessageUtil.sendMessage(player, "displayVideoWrongTargetBlock").let { 0 }

        val worldKey = RegionUtil.getPlayerLevelKey(player)
        val data = DisplayManager.isContains(worldKey, targetPos)
            ?: return MessageUtil.sendMessage(player, "noDisplay").let { 0 }

        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uuid, VanillaDisplayActions.isAdmin(player))
            )
        ) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return 0
        }

        val wasSync = data.isSync
        data.url = YouTubeUrls.watchUrl(videoId)
        data.lang = LanguageTag.canonicalAudioCode(langRaw).value
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        VanillaPacketUtil.sendDisplayInfo(receivers, data)
        if (wasSync) StateManager.resetAndBroadcast(data.id, receivers)
        TimelineManager.onVideoChanged(data)

        MessageUtil.sendMessage(player, "settedURL")
        return 1
    }
}
