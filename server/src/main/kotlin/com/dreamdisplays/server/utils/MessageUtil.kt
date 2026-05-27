package com.dreamdisplays.server.utils

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.Main
import com.google.gson.Gson
import com.mojang.serialization.JsonOps
import net.kyori.adventure.text.Component
import net.minecraft.network.chat.Component as NmsComponent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked

/**
 * Message utilities. Provides methods for sending localized and formatted messages to players and command senders,
 * abstracting away the differences between `Adventure Components`, legacy color-coded strings, and JSON objects.
 *
 * Also handles localization by looking up message keys in the config and substituting player-specific values.
 * Used throughout the plugin for consistent message formatting and localization support.
 */
object MessageUtil {
    private val gson by lazy { Gson() }
    @PaperOnly private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()
    @PaperOnly private val gsonSerializer = GsonComponentSerializer.gson()

    /** Sends a localized message identified by [messageKey] to [sender]. */
    @PaperOnly @NullMarked fun sendMessage(sender: CommandSender?, messageKey: String) {
        val message = Main.config.getMessageForPlayer(sender as? Player, messageKey)
        sendColoredMessage(sender, message)
    }

    /** Sends [message] to [sender], auto-detecting Component / legacy string / JSON forms. */
    @PaperOnly @NullMarked fun sendColoredMessage(sender: CommandSender?, message: Any?) {
        if (sender == null || message == null) return
        when (message) {
            is Component -> sender.sendMessage(message)
            is String -> sender.sendMessage(legacySerializer.deserialize(message))
            else -> sender.sendMessage(gsonSerializer.deserialize(gson.toJson(message)))
        }
    }

    /** Sends an already-built `Adventure` [component] to [sender], silently ignoring nulls. */
    @PaperOnly @NullMarked fun sendComponent(sender: CommandSender?, component: Component?) {
        if (sender == null || component == null) return
        sender.sendMessage(component)
    }

    /** Sends a localized message identified by [messageKey] to [player]. */
    @FabricOnly fun sendMessage(player: ServerPlayer?, messageKey: String) {
        val config = com.dreamdisplays.server.Server.config
        val message = config.getMessageForPlayer(player, messageKey)
        sendColoredMessage(player, message)
    }

    /** Sends [message] to [player], converting strings / maps to `NMS Component`. */
    @FabricOnly fun sendColoredMessage(player: ServerPlayer?, message: Any?) {
        if (player == null || message == null) return
        player.sendSystemMessage(toNmsComponent(message))
    }

    @FabricOnly private fun toNmsComponent(message: Any): NmsComponent {
        return when (message) {
            is String -> parseAmpersandLegacy(message)
            is Map<*, *> -> runCatching {
                val jsonElement = gson.toJsonTree(message)
                ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, jsonElement).result().orElse(null)
                    ?: parseAmpersandLegacy(message.toString())
            }.getOrElse { parseAmpersandLegacy(message.toString()) }
            else -> parseAmpersandLegacy(message.toString())
        }
    }

    /** Converts `&` color codes to a plain NMS text component (strips formatting on `Fabric`). */
    @FabricOnly private fun parseAmpersandLegacy(text: String): NmsComponent =
        NmsComponent.literal(text.replace(Regex("&[0-9a-fA-FrRlLoOnNmMkK]"), ""))
}
