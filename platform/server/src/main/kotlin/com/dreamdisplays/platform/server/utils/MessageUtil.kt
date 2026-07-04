package com.dreamdisplays.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.util.toJsonString
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.minecraft.core.registries.BuiltInRegistries
//? if >=1.21.11 {
import net.minecraft.data.AtlasIds
//?}
import net.minecraft.network.chat.Component as NmsComponent
import net.minecraft.network.chat.MutableComponent
import net.kyori.adventure.text.serializer.json.JSONComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
//? if >=1.21.11 {
import net.minecraft.network.chat.contents.ObjectContents as NmsObjectContents
import net.minecraft.network.chat.contents.objects.AtlasSprite
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import net.minecraft.server.level.ServerPlayer
//? if >=26 {
import net.minecraft.world.item.ItemStackTemplate
//?} else
/*import net.minecraft.world.item.ItemStack*/
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked
import java.net.URI
import java.util.Optional

/**
 * Message utilities. Provides methods for sending localized and formatted messages to players and command senders,
 * abstracting away the differences between `Adventure Components`, legacy color-coded strings, and JSON objects.
 *
 * Also handles localization by looking up message keys in the config and substituting player-specific values.
 * Used throughout the plugin for consistent message formatting and localization support.
 */
object MessageUtil {
    /** Legacy and JSON serializers for legacy color-coded strings. */
    @PaperOnly
    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    /** Legacy and JSON serializers for `Adventure Components`. */
    @PaperOnly
    private val jsonSerializer = JSONComponentSerializer.json()

    /** Sends a localized message identified by [messageKey] to [sender]. */
    @PaperOnly
    @NullMarked
    fun sendMessage(sender: CommandSender?, messageKey: String, vararg args: Any) {
        val raw = PaperServer.config.getMessageForPlayer(sender as? Player, messageKey)
        val message = if (args.isNotEmpty() && raw is String) raw.format(*args) else raw
        sendColoredMessage(sender, message)
    }

    /** Sends [message] to [sender], auto-detecting Component / legacy string / JSON forms. */
    @PaperOnly
    @NullMarked
    fun sendColoredMessage(sender: CommandSender?, message: Any?) {
        if (sender == null || message == null) return
        when (message) {
            is Component -> sender.sendMessage(message)
            is String -> sender.sendMessage(legacySerializer.deserialize(message))
            else -> sender.sendMessage(deserializeJsonComponent(message))
        }
    }

    /** Deserializes a JSON component represented as Map/List primitives using Kotlin serialization. */
    @PaperOnly
    fun deserializeJsonComponent(message: Any?): Component =
        runCatching { jsonSerializer.deserialize(message.toJsonString()) }
            .getOrElse { Component.text(message.toString()) }

    /** Sends an already-built `Adventure` [component] to [sender], silently ignoring nulls. */
    @PaperOnly
    @NullMarked
    fun sendComponent(sender: CommandSender?, component: Component?) {
        if (sender == null || component == null) return
        sender.sendMessage(component)
    }

    /** Returns the localized string for [key] in [sender]'s language, or [key] when missing. */
    @PaperOnly
    fun messageFor(sender: CommandSender, key: String): String =
        PaperServer.config.getMessageForPlayer(sender as? Player, key) as? String ?: key

    /** Returns the localized string for [key], substituting `{0}`, `{1}`, ... with [values]. */
    @PaperOnly
    fun formatIndexed(sender: CommandSender, key: String, vararg values: String): String =
        applyPlaceholders(messageFor(sender, key), *values)

    /** Returns the localized string for [key], substituting `%s`/`%d`-style [values] via `String.format`. */
    @PaperOnly
    fun formatPrintf(sender: CommandSender, key: String, vararg values: Any): String {
        val template = messageFor(sender, key)
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }

    /**
     * Sends a localized message to [sender], replacing `{0}`, `{1}` ... placeholders with
     * inline item sprite icons for each [materials] entry. Hovering reveals the item tooltip.
     */
    @PaperOnly
    @NullMarked
    fun sendMessageWithMaterials(sender: CommandSender?, messageKey: String, vararg materials: Material) {
        if (sender == null) return
        val rawMessage = PaperServer.config.getMessageForPlayer(sender as? Player, messageKey) as? String ?: run {
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
        // asComponent() (from ComponentLike) has a stable Component return across versions;
        // build() is covariantly overridden (BuildableComponent <=1.21.11, Component >=26), whose
        // differing descriptor would throw NoSuchMethodError on the family this jar wasn't built on.
        sender.sendMessage(builder.asComponent())
    }

    /**
     * Block items live in the `minecraft:blocks` atlas (`block/<name>`); pure items live in
     * `minecraft:items` (`item/<name>`). The sprite / object hover form needs the Adventure API added
     * in 1.21.11, so it is delegated to [SpriteHoverRenderer] behind a runtime version gate; older
     * servers fall back to a plain hover-text label so the single jar links on every version.
     */
    @PaperOnly
    private fun materialSpriteComponent(mat: Material): Component {
        if (ServerVersion.isAtLeast_1_21_11) return SpriteHoverRenderer.render(mat)
        val key = "${mat.key().namespace()}:${mat.key().value()}"
        return Component.text(key).hoverEvent(HoverEvent.showText(Component.text(key)))
    }

    /** Sends a localized message identified by [messageKey] to [player]. */
    fun sendMessage(player: ServerPlayer?, messageKey: String, vararg args: Any) {
        val config = VanillaServerState.config
        val raw = config.getMessageForPlayer(player, messageKey)
        val message = if (args.isNotEmpty() && raw is String) raw.format(*args) else raw
        sendColoredMessage(player, message)
    }

    /**
     * Interpolates `%s` placeholders in a raw config [message] with [args], preserving its form:
     * plain strings are formatted directly, JSON component maps have `%s` substituted inside their
     * text fields so the resulting component still renders (rather than being dumped via `toString`).
     */
    fun formatMessage(message: Any?, vararg args: String): Any? = when (message) {
        null -> null
        is String -> if (args.isEmpty()) message else String.format(message, *args)
        is Map<*, *> -> replacePlaceholders(message, args, intArrayOf(0))
        is List<*> -> replacePlaceholders(message, args, intArrayOf(0))
        else -> message
    }

    /** Returns the localized string for [key] in [player]'s language, or [key] when missing. */
    fun messageFor(player: ServerPlayer?, key: String): String =
        VanillaServerState.config.getMessageForPlayer(player, key) as? String ?: key

    /** Returns the localized string for [key], substituting `{0}`, `{1}`, ... with [values]. */
    fun formatIndexed(player: ServerPlayer?, key: String, vararg values: String): String =
        applyPlaceholders(messageFor(player, key), *values)

    /** Returns the localized string for [key], substituting `%s`/`%d`-style [values] via `String.format`. */
    fun formatPrintf(player: ServerPlayer?, key: String, vararg values: Any): String {
        val template = messageFor(player, key)
        return runCatching { String.format(template, *values) }.getOrElse { template }
    }

    /** Replaces `{0}`, `{1}`, ... placeholders in [template] with the matching value from [values]. */
    fun applyPlaceholders(template: String, vararg values: String): String {
        var result = template
        values.forEachIndexed { index, value -> result = result.replace("{$index}", value) }
        return result
    }

    /** Sends [message] to [player], converting strings / maps to `NMS Component`. */
    fun sendColoredMessage(player: ServerPlayer?, message: Any?) {
        if (player == null || message == null) return
        player.sendSystemMessage(toNmsComponent(message))
    }

    /** Converts an `Adventure` [component] to a `NMS Component`. */
    private fun toNmsComponent(message: Any): NmsComponent {
        return when (message) {
            is String -> parseAmpersandLegacy(message)
            is Map<*, *> -> nmsComponentFromJsonValue(message) ?: parseAmpersandLegacy(message.toString())
            is List<*> -> nmsComponentFromJsonValue(message) ?: parseAmpersandLegacy(message.toString())
            else -> parseAmpersandLegacy(message.toString())
        }
    }

    /** Replace placeholders in a JSON component map with [args]. */
    private fun replacePlaceholders(value: Any?, args: Array<out String>, index: IntArray): Any? = when (value) {
        is String -> {
            var formatted: String = value
            var placeholder = formatted.indexOf("%s")
            while (placeholder >= 0 && index[0] < args.size) {
                formatted = formatted.substring(0, placeholder) +
                    args[index[0]++] +
                    formatted.substring(placeholder + 2)
                placeholder = formatted.indexOf("%s", placeholder)
            }
            formatted
        }
        is Map<*, *> -> value.entries.associate { (key, entryValue) ->
            key.toString() to replacePlaceholders(entryValue, args, index)
        }
        is List<*> -> value.map { replacePlaceholders(it, args, index) }
        else -> value
    }

    /** NMS component builder from a JSON component map. */
    private fun nmsComponentFromJsonValue(value: Any?): NmsComponent? = when (value) {
        is String -> NmsComponent.literal(value)
        is Map<*, *> -> {
            val root = when {
                value.string("translate") != null -> NmsComponent.translatable(value.string("translate")!!)
                else -> NmsComponent.literal(value.string("text") ?: "")
            }
            value.list("extra")?.forEach { child ->
                nmsComponentFromJsonValue(child)?.let(root::append)
            }
            root.withJsonStyle(value)
        }
        is List<*> -> {
            val root = NmsComponent.empty()
            value.forEach { child -> nmsComponentFromJsonValue(child)?.let(root::append) }
            root
        }
        else -> null
    }

    /** Applies JSON component style from a JSON component map. */
    private fun MutableComponent.withJsonStyle(value: Map<*, *>): MutableComponent = withStyle { base ->
        var style = base
        value.string("color")?.let { color ->
            val parsedColor = net.minecraft.network.chat.TextColor.parseColor(color).result().orElse(null)
            if (parsedColor != null) style = style.withColor(parsedColor)
        }
        value.bool("bold")?.let { style = style.withBold(it) }
        value.bool("italic")?.let { style = style.withItalic(it) }
        value.bool("underlined")?.let { style = style.withUnderlined(it) }
        value.bool("strikethrough")?.let { style = style.withStrikethrough(it) }
        value.bool("obfuscated")?.let { style = style.withObfuscated(it) }

        val clickEvent = value.map("clickEvent")
        if (clickEvent?.string("action") == "open_url") {
            clickEvent.string("value")?.let { url ->
                runCatching { URI.create(url) }.getOrNull()?.let {
                    //? if >=1.21.11 {
                    style = style.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(it))
                    //?} else
                    /*style = style.withClickEvent(
                        net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, url)
                    )*/
                }
            }
        }

        val hoverEvent = value.map("hoverEvent")
        if (hoverEvent?.string("action") == "show_text") {
            val hoverComponent = nmsComponentFromJsonValue(hoverEvent["value"])
                ?: hoverEvent.string("value")?.let(NmsComponent::literal)
            hoverComponent?.let {
                //? if >=1.21.11 {
                style = style.withHoverEvent(net.minecraft.network.chat.HoverEvent.ShowText(it))
                //?} else
                /*style = style.withHoverEvent(
                    net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, it)
                )*/
            }
        }
        style
    }

    /** Type-safe accessors for JSON component map values. */
    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    /** Type-safe accessors for JSON component map values. */
    private fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean

    /** Type-safe accessors for JSON component map values. */
    private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>

    /** Type-safe accessors for JSON component list values. */
    private fun Map<*, *>.list(key: String): List<*>? = this[key] as? List<*>

    /**
     * Sends a localized message to [player], replacing `{0}`, `{1}` ... placeholders with
     * inline item sprite icons for each [materialKeys] entry (e.g. `"minecraft:diamond_axe"`).
     * Hovering reveals the item tooltip.
     */
    fun sendMessageWithMaterials(player: ServerPlayer?, messageKey: String, vararg materialKeys: String) {
        if (player == null) return
        val rawMessage = VanillaServerState.config.getMessageForPlayer(player, messageKey) as? String ?: run {
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
                val spriteComponent =
                    //? if >=1.21.11 {
                    run {
                val item = BuiltInRegistries.ITEM.getValue(itemId)
                val isBlock = item is net.minecraft.world.item.BlockItem
                val atlasId = if (isBlock) AtlasIds.BLOCKS else AtlasIds.ITEMS
                val spriteId = Identifier.fromNamespaceAndPath(
                    itemId.namespace,
                    (if (isBlock) "block/" else "item/") + itemId.path
                )
                val atlasSprite = AtlasSprite(atlasId, spriteId)
                //? if >=26 {
                val hoverEvent = net.minecraft.network.chat.HoverEvent.ShowItem(ItemStackTemplate(item))
                val spriteComponent = MutableComponent.create(NmsObjectContents(atlasSprite, Optional.empty()))
                    //?} else
                    /*val hoverEvent = net.minecraft.network.chat.HoverEvent.ShowItem(ItemStack(item))
                    val spriteComponent = MutableComponent.create(NmsObjectContents(atlasSprite))*/
                    spriteComponent.withStyle { it.withHoverEvent(hoverEvent) }
                    }
                    //?} else
                    /*NmsComponent.literal(itemId.toString())*/
                root.append(spriteComponent)
            }
            lastIndex = match.range.last + 1
        }
        val remaining = rawMessage.substring(lastIndex)
        if (remaining.isNotEmpty()) root.append(parseAmpersandLegacy(remaining))
        player.sendSystemMessage(root)
    }

    /** Converts `&` color codes to a plain NMS text component (strips formatting on `Fabric`/`NeoForge`). */
    private fun parseAmpersandLegacy(text: String): NmsComponent =
        NmsComponent.literal(text.replace(Regex("&[0-9a-fA-FrRlLoOnNmMkK]"), ""))
}
