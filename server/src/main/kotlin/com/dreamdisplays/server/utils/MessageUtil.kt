package com.dreamdisplays.server.utils

import io.github.arsmotorin.ofrat.FabricOnly
import io.github.arsmotorin.ofrat.PaperOnly

import com.dreamdisplays.server.Main
import com.google.gson.Gson
import com.mojang.serialization.JsonOps
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.`object`.ObjectContents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.AtlasIds
import net.minecraft.network.chat.Component as NmsComponent
import net.minecraft.network.chat.MutableComponent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.contents.ObjectContents as NmsObjectContents
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
//? if >=26 {
import net.minecraft.world.item.ItemStackTemplate
//?} else
/*import net.minecraft.world.item.ItemStack*/
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.util.Optional

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

    /**
     * Sends a localized message to [sender], replacing `{0}`, `{1}` ... placeholders with
     * inline item sprite icons for each [materials] entry. Hovering reveals the item tooltip.
     */
    @PaperOnly @NullMarked fun sendMessageWithMaterials(sender: CommandSender?, messageKey: String, vararg materials: Material) {
        if (sender == null) return
        val rawMessage = Main.config.getMessageForPlayer(sender as? Player, messageKey) as? String ?: run {
            sendMessage(sender, messageKey)
            return
        }
        val pattern = Regex("""\{(\d+)}""")
        val builder = Component.text()
        var lastIndex = 0
        for (match in pattern.findAll(rawMessage)) {
            val textBefore = rawMessage.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) builder.append(legacySerializer.deserialize(textBefore))
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index < materials.size) {
                builder.append(materialSpriteComponent(materials[index]))
            }
            lastIndex = match.range.last + 1
        }
        val remaining = rawMessage.substring(lastIndex)
        if (remaining.isNotEmpty()) builder.append(legacySerializer.deserialize(remaining))
        sender.sendMessage(builder.build())
    }

    /**
     * Block items live in the `minecraft:blocks` atlas (`block/<name>`);
     * pure items live in `minecraft:items` (`item/<name>`).
     */
    @PaperOnly private fun materialSpriteComponent(mat: Material): Component {
        val ns = mat.key().namespace()
        val name = mat.key().value()
        val atlas: Key
        val spriteKey: Key
        if (mat.isBlock) {
            atlas = Key.key("minecraft", "blocks")
            spriteKey = Key.key(ns, "block/$name")
        } else {
            atlas = Key.key("minecraft", "items")
            spriteKey = Key.key(ns, "item/$name")
        }
        return Component.`object`(ObjectContents.sprite(atlas, spriteKey))
            .hoverEvent(HoverEvent.showItem(mat.key(), 1))
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

    /**
     * Sends a localized message to [player], replacing `{0}`, `{1}` ... placeholders with
     * inline item sprite icons for each [materialKeys] entry (e.g. `"minecraft:diamond_axe"`).
     * Hovering reveals the item tooltip.
     */
    @FabricOnly fun sendMessageWithMaterials(player: ServerPlayer?, messageKey: String, vararg materialKeys: String) {
        if (player == null) return
        val config = com.dreamdisplays.server.Server.config
        val rawMessage = config.getMessageForPlayer(player, messageKey) as? String ?: run {
            sendMessage(player, messageKey)
            return
        }
        val pattern = Regex("""\{(\d+)}""")
        val root = NmsComponent.literal("")
        var lastIndex = 0
        for (match in pattern.findAll(rawMessage)) {
            val textBefore = rawMessage.substring(lastIndex, match.range.first)
            if (textBefore.isNotEmpty()) root.append(parseAmpersandLegacy(textBefore))
            val index = match.groupValues[1].toIntOrNull()
            if (index != null && index < materialKeys.size) {
                val itemId = Identifier.parse(materialKeys[index])
                val item = BuiltInRegistries.ITEM.getValue(itemId)
                val isBlock = item is net.minecraft.world.item.BlockItem
                val atlasId = if (isBlock) AtlasIds.BLOCKS else AtlasIds.ITEMS
                val spriteId = Identifier.fromNamespaceAndPath(itemId.namespace, (if (isBlock) "block/" else "item/") + itemId.path)
                val atlasSprite = AtlasSprite(atlasId, spriteId)
                //? if >=26 {
                val hoverEvent = net.minecraft.network.chat.HoverEvent.ShowItem(ItemStackTemplate(item))
                val spriteComponent = MutableComponent.create(NmsObjectContents(atlasSprite, Optional.empty()))
                //?} else
                /*val hoverEvent = net.minecraft.network.chat.HoverEvent.ShowItem(ItemStack(item))
                val spriteComponent = MutableComponent.create(NmsObjectContents(atlasSprite))*/
                    .withStyle { it.withHoverEvent(hoverEvent) }
                root.append(spriteComponent)
            }
            lastIndex = match.range.last + 1
        }
        val remaining = rawMessage.substring(lastIndex)
        if (remaining.isNotEmpty()) root.append(parseAmpersandLegacy(remaining))
        player.sendSystemMessage(root)
    }

    /** Converts `&` color codes to a plain NMS text component (strips formatting on `Fabric`). */
    @FabricOnly private fun parseAmpersandLegacy(text: String): NmsComponent =
        NmsComponent.literal(text.replace(Regex("&[0-9a-fA-FrRlLoOnNmMkK]"), ""))
}
