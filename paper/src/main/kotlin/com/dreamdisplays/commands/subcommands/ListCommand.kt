package com.dreamdisplays.commands.subcommands

import com.dreamdisplays.Main
import com.dreamdisplays.datatypes.DisplayData
import com.dreamdisplays.managers.DisplayManager.getDisplays
import com.dreamdisplays.utils.MessageUtil.sendColoredMessage
import com.dreamdisplays.utils.MessageUtil.sendComponent
import com.dreamdisplays.utils.MessageUtil.sendMessage
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import kotlin.math.max

class ListCommand : SubCommand {
    private companion object {
        const val PAGE_SIZE = 10
        const val FILTER_MINE = "mine"
        const val FILTER_WORLD = "world"
        const val FILTER_OWNER = "owner"
        const val FILTER_SYNC = "sync"
        val LEGACY_SERIALIZER: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()
    }

    private data class ListQuery(
        val displays: List<DisplayData>,
        val page: Int,
    )

    override val name = "list"
    override val permission = Main.config.permissions.list

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val displays = sortedDisplays()
        if (displays.isEmpty()) {
            sendMessage(sender, "noDisplaysFound")
            return
        }

        val ownerNameCache = mutableMapOf<UUID, String?>()
        val query = parseQuery(sender, args, displays, ownerNameCache) ?: return
        if (query.displays.isEmpty()) {
            sendMessage(sender, "noDisplaysFound")
            return
        }
        val totalPages = pageCount(query.displays.size)
        val page = query.page.coerceIn(1, totalPages)
        val startIndex = (page - 1) * PAGE_SIZE
        val endExclusive = minOf(startIndex + PAGE_SIZE, query.displays.size)
        val pageDisplays = query.displays.subList(startIndex, endExclusive)

        sendMessage(sender, "displayListHeader")
        sendColoredMessage(
            sender,
            msgf(
                sender,
                "displayListPageLine",
                page.toString(),
                totalPages.toString(),
                query.displays.size.toString()
            )
        )

        pageDisplays.forEachIndexed { localIndex, d ->
            val index = startIndex + localIndex + 1
            val owner = getOwnerName(d.ownerId, ownerNameCache) ?: msg(sender, "displayListUnknownOwner")
            val worldName = d.pos1.world?.name ?: msg(sender, "displayListUnknownWorld")
            val idShort = d.id.toString().substring(0, 8)
            val url = d.url.ifBlank { msg(sender, "displayListUnavailableUrl") }
            val baseLine = msgf(
                sender,
                "displayListEntry",
                index.toString(),
                owner,
                d.pos1.blockX.toString(),
                d.pos1.blockY.toString(),
                d.pos1.blockZ.toString(),
                url
            )
            val details = msgf(
                sender,
                "displayListDetails",
                worldName,
                d.width.toString(),
                d.height.toString(),
                d.isSync.toString(),
                idShort
            )
            val fullLine = baseLine + details

            if (sender !is Player) {
                sendColoredMessage(sender, fullLine)
                return@forEachIndexed
            }

            val component = LEGACY_SERIALIZER.deserialize(fullLine)
                .append(
                    text(msg(sender, "displayListTpButton"))
                        .color(NamedTextColor.GREEN)
                        .clickEvent(
                            ClickEvent.runCommand(
                                "/tp ${d.pos1.blockX} ${d.pos1.blockY} ${d.pos1.blockZ}"
                            )
                        )
                )

            val finalComponent = if (d.url.isBlank()) {
                component
            } else {
                component.append(
                    text(msg(sender, "displayListUrlButton")).color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.openUrl(d.url))
                )
            }

            sendComponent(sender, finalComponent)
        }
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        val displays = sortedDisplays()
        val ownerNameCache = mutableMapOf<UUID, String?>()

        return when (args.size) {
            2 -> mutableSetOf<String>().apply {
                add(FILTER_MINE)
                add(FILTER_WORLD)
                add(FILTER_OWNER)
                add(FILTER_SYNC)
                addAll(pageSuggestions(displays.size))
            }.sorted()

            3 -> when (args[1]?.lowercase()) {
                FILTER_MINE -> {
                    if (sender !is Player) {
                        emptyList()
                    } else {
                        pageSuggestions(displays.count { it.ownerId == sender.uniqueId })
                    }
                }

                FILTER_SYNC -> pageSuggestions(displays.count { it.isSync })
                FILTER_WORLD -> displays
                    .mapNotNull { it.pos1.world?.name }
                    .distinct()
                    .sorted()

                FILTER_OWNER -> displays
                    .mapNotNull { getOwnerName(it.ownerId, ownerNameCache) }
                    .distinct()
                    .sorted()

                else -> emptyList()
            }

            4 -> when (args[1]?.lowercase()) {
                FILTER_WORLD -> pageSuggestions(filterByWorld(displays, args[2].orEmpty()).size)
                FILTER_OWNER -> pageSuggestions(filterByOwner(displays, args[2].orEmpty(), ownerNameCache).size)
                else -> emptyList()
            }

            else -> emptyList()
        }
    }

    private fun parseQuery(
        sender: CommandSender,
        args: Array<String?>,
        displays: List<DisplayData>,
        ownerNameCache: MutableMap<UUID, String?>,
    ): ListQuery? {
        if (args.size == 1) return ListQuery(displays, 1)

        val selector = args.getOrNull(1)?.lowercase() ?: return wrong(sender)
        val pageOnly = selector.toIntOrNull()
        if (pageOnly != null) {
            if (args.size != 2) return wrong(sender)
            return ListQuery(displays, pageOnly)
        }

        return when (selector) {
            FILTER_MINE -> {
                if (sender !is Player) {
                    sendMessage(sender, "commandPlayersOnly")
                    return null
                }
                if (args.size !in 2..3) return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(2)) ?: return null
                ListQuery(displays.filter { it.ownerId == sender.uniqueId }, page)
            }

            FILTER_SYNC -> {
                if (args.size !in 2..3) return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(2)) ?: return null
                ListQuery(displays.filter { it.isSync }, page)
            }

            FILTER_WORLD -> {
                if (args.size !in 3..4) return wrong(sender)
                val worldName = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(3)) ?: return null
                ListQuery(filterByWorld(displays, worldName), page)
            }

            FILTER_OWNER -> {
                if (args.size !in 3..4) return wrong(sender)
                val ownerName = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(3)) ?: return null
                ListQuery(filterByOwner(displays, ownerName, ownerNameCache), page)
            }

            else -> wrong(sender)
        }
    }

    private fun parseOptionalPage(sender: CommandSender, rawPage: String?): Int? {
        if (rawPage == null) return 1
        val page = rawPage.toIntOrNull()
        if (page == null) {
            sendMessage(sender, "displayWrongCommand")
            return null
        }
        return page
    }

    private fun filterByWorld(displays: List<DisplayData>, worldName: String): List<DisplayData> {
        return displays.filter { it.pos1.world?.name?.equals(worldName, ignoreCase = true) == true }
    }

    private fun filterByOwner(
        displays: List<DisplayData>,
        ownerName: String,
        ownerNameCache: MutableMap<UUID, String?>,
    ): List<DisplayData> {
        return displays.filter { display ->
            getOwnerName(display.ownerId, ownerNameCache)?.equals(ownerName, ignoreCase = true) == true ||
                    display.ownerId.toString().equals(ownerName, ignoreCase = true)
        }
    }

    private fun getOwnerName(ownerId: UUID, ownerNameCache: MutableMap<UUID, String?>): String? {
        return ownerNameCache.getOrPut(ownerId) { Bukkit.getOfflinePlayer(ownerId).name }
    }

    private fun sortedDisplays(): List<DisplayData> {
        return getDisplays()
            .sortedWith(
                compareBy<DisplayData>(
                    { it.pos1.world?.name ?: "" },
                    { it.pos1.blockX },
                    { it.pos1.blockY },
                    { it.pos1.blockZ },
                    { it.id.toString() }
                )
            )
    }

    private fun pageCount(size: Int): Int = max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun pageSuggestions(size: Int): List<String> = (1..pageCount(size)).map { it.toString() }

    private fun wrong(sender: CommandSender): ListQuery? {
        sendMessage(sender, "displayWrongCommand")
        return null
    }

    private fun applyPlaceholders(template: String, vararg values: String): String {
        var result = template
        values.forEachIndexed { index, value ->
            result = result.replace("{$index}", value)
        }
        return result
    }

    private fun msg(sender: CommandSender, key: String): String {
        return Main.config.getMessageForPlayer(sender as? Player, key) as? String ?: key
    }

    private fun msgf(sender: CommandSender, key: String, vararg values: String): String {
        return applyPlaceholders(msg(sender, key), *values)
    }
}
