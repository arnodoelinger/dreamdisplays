package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main
import com.dreamdisplays.managers.DisplayManager.getReceivers
import com.dreamdisplays.managers.DisplayManager.isContains
import com.dreamdisplays.managers.DisplayManager.sendUpdate
import com.dreamdisplays.utils.MessageUtil
import com.dreamdisplays.utils.YouTubeUtil
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

class VideoCommand : SubCommand {

    override val name = "video"
    override val permission = Main.config.permissions.video
    override val playerOnly = true

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

        val data = isContains(block.location)
        if (data == null) {
            MessageUtil.sendMessage(player, "noDisplay")
            return
        }

        if (data.ownerId != player.uniqueId) {
            MessageUtil.sendMessage(player, "displayVideoNotOwner")
            return
        }

        data.apply {
            url = "https://youtube.com/watch?v=$code"
            lang = normalizeLangCode(args.getOrNull(2).orEmpty())
            isSync = false
        }

        sendUpdate(data, getReceivers(data))

        MessageUtil.sendMessage(player, "settedURL")
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size == 3) {
            return languageSuggestions
        }
        return emptyList()
    }

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
