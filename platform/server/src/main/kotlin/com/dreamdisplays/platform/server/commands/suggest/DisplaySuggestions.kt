package com.dreamdisplays.platform.server.commands.suggest

import com.dreamdisplays.platform.server.commands.subcommands.FullscreenCommand
import com.dreamdisplays.platform.server.commands.subcommands.ListFilter
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.proxy.ProxyNetwork
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import java.util.*

/**
 * What `/display` offers on tab, shared by the `Paper` and `Fabric` / `NeoForge` trees.
 * Every tooltip is a message key from the server language files.
 */
internal object DisplaySuggestions {
    private const val PAGE_SIZE = 10

    private val THIS = Suggestion("this", "suggestThis")

    private val LINKS = listOf(
        Suggestion("https://www.youtube.com/watch?v=", "suggestLinkYoutube"),
        Suggestion("https://youtu.be/", "suggestLinkYoutubeShort"),
        Suggestion("https://www.twitch.tv/", "suggestLinkTwitch"),
        Suggestion("https://vimeo.com/", "suggestLinkVimeo"),
        Suggestion("https://kick.com/", "suggestLinkKick"),
        Suggestion("https://", "suggestLinkDirect"),
    )

    private val FILTERS = mapOf(
        ListFilter.MINE to "suggestFilterMine",
        ListFilter.WORLD to "suggestFilterWorld",
        ListFilter.OWNER to "suggestFilterOwner",
        ListFilter.SYNC to "suggestFilterSync",
    )

    private val SELECTORS = listOf(
        Suggestion("@a", "suggestSelectorAll"),
        Suggestion("@p", "suggestSelectorNearest"),
        Suggestion("@r", "suggestSelectorRandom"),
        Suggestion("@s", "suggestSelectorSelf"),
        Suggestion("@e", "suggestSelectorEntities"),
    )

    private val QUALITIES = listOf(
        Suggestion("auto", "suggestQualityAuto"),
        Suggestion("360"),
        Suggestion("480"),
        Suggestion("720"),
        Suggestion("1080"),
    )

    private val VOLUMES = listOf(
        Suggestion("0", "suggestVolumeMuted"),
        Suggestion("50"),
        Suggestion("100"),
        Suggestion("150"),
        Suggestion("200", "suggestVolumeMax"),
    )

    private val RADII = listOf("8", "16", "32", "64", "128").map { Suggestion(it, "suggestRadiusBlocks") }

    /** `this`, then every display by name or short id when [remote] targeting is allowed. */
    fun targets(typed: String, remote: Boolean): List<Suggestion> =
        (listOf(THIS) + if (remote) displays() else emptyList()).startingWith(typed)

    /** The name [token] currently carries, offered so it can be edited instead of retyped. */
    fun currentName(token: String, typed: String): List<Suggestion> {
        if (token.equals("this", ignoreCase = true)) return emptyList()
        val name = DisplayManager.resolveByIdOrPrefix(token)?.name ?: return emptyList()
        return listOf(Suggestion(name, "suggestCurrentName")).startingWith(typed)
    }

    /** Link starters while the link is being typed, then [languages] once a space follows it. */
    fun link(remaining: String, typed: String, languages: () -> List<String>): List<Suggestion> {
        if (' ' !in remaining) return links(typed)
        if (remaining.count { it == ' ' } > 1) return emptyList()
        return languages().filter { it.startsWith(typed, ignoreCase = true) }.map { Suggestion(it, "suggestLanguage") }
    }

    /** The link starters [typed] could still grow into. */
    fun links(typed: String): List<Suggestion> =
        LINKS.filter { it.text.length > typed.length && it.text.startsWith(typed, ignoreCase = true) }

    /**
     * Every minute of the day as `HH:mm`, local to a player [offsetMinutes] from UTC. With nothing
     * typed it is the next minute, then a rolling window of the next two hours.
     */
    fun scheduleTimes(offsetMinutes: Int, typed: String): List<Suggestion> {
        val nowMinute = ScheduleTimeUtil.minuteOfDay(ScheduleTimeUtil.currentSecondOfDay(offsetMinutes))
        val firstMinute = (nowMinute + 1) % 1440
        val minutes = if (typed.isBlank()) {
            (0 until 120).map { (firstMinute + it) % 1440 }
        } else {
            (0 until 1440)
                .map { (firstMinute + it) % 1440 }
                .filter { ScheduleTimeUtil.format(it * 60).startsWith(typed, ignoreCase = true) }
                .take(150)
        }
        return minutes.map { minute ->
            val text = ScheduleTimeUtil.format(minute * 60)
            if (minute == firstMinute) return@map Suggestion(text, "suggestTimeNow")
            val secondsAhead = ScheduleTimeUtil.secondsUntil(minute * 60, offsetMinutes)
            Suggestion(text, "suggestTimeIn", listOf(ScheduleTimeUtil.compactCountdown(secondsAhead)))
        }
    }

    /** The `/display list` filters, then the page numbers of the unfiltered list. */
    fun listFilters(typed: String): List<Suggestion> =
        (ListFilter.entries.map { Suggestion(it.token, FILTERS[it]) } + pages(DisplayManager.getDisplays().size))
            .startingWith(typed)

    /** Page numbers for a list of [size] displays. */
    fun pages(size: Int): List<Suggestion> {
        val count = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)
        return (1..count).map { Suggestion(it.toString(), "suggestPage", listOf(it.toString(), count.toString())) }
    }

    /** [names] of players, best match for [typed] first. */
    fun players(names: List<String>, typed: String): List<Suggestion> =
        rank(names.sortedBy { it.lowercase() }, typed).map { Suggestion(it, "suggestOnlinePlayer") }

    /** Selectors, then [names], for one entry of a comma-separated player list. */
    fun playerList(names: List<String>, typed: String): List<Suggestion> =
        SELECTORS.startingWith(typed) + players(names, typed)

    /** Backend servers a fullscreen broadcast can be scoped to, then `global` for the whole network. */
    fun servers(typed: String): List<Suggestion> =
        (ProxyNetwork.serverNames().sorted().map { Suggestion(it, "suggestServer") } +
                Suggestion("global", "suggestServerGlobal")).startingWith(typed)

    /** Live fullscreen sessions, then `all`. */
    fun fullscreenStops(typed: String): List<Suggestion> =
        FullscreenCommand.stopSuggestions()
            .map { Suggestion(it, if (it == "all") "suggestStopAll" else "suggestStopSession") }
            .startingWith(typed)

    /** Quality caps for a fullscreen broadcast. */
    fun qualities(typed: String): List<Suggestion> = QUALITIES.startingWith(typed)

    /** Round volume steps for a fullscreen broadcast. */
    fun volumes(typed: String): List<Suggestion> = VOLUMES.startingWith(typed)

    /** Round radius steps for a fullscreen broadcast. */
    fun radii(typed: String): List<Suggestion> = RADII.startingWith(typed)

    /** One coordinate of where the sender stands, as a radius origin. */
    fun coordinate(value: Double?, axis: String, typed: String): List<Suggestion> {
        val text = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: return emptyList()
        return listOf(Suggestion(text, "suggestCoordinate", listOf(axis.uppercase()))).startingWith(typed)
    }

    /** Every display by name or short ID, alphabetically. */
    private fun displays(): List<Suggestion> =
        DisplayManager.getDisplays()
            .sortedBy { it.shortLabel.lowercase() }
            .map { describe(it) }

    /** A display with its size as the tooltip, plus its id when it goes by a name. */
    private fun describe(display: DisplayData): Suggestion {
        val size = listOf(display.width.toString(), display.height.toString())
        if (display.name == null) return Suggestion(display.shortLabel, "suggestDisplay", size)
        return Suggestion(display.shortLabel, "suggestDisplayNamed", size + display.id.toString().take(8))
    }
}
