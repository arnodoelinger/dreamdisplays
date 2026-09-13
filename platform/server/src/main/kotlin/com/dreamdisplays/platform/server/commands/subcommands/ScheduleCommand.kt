package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.playback.ScheduledPlaybackManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import kotlin.time.Clock

/** The literal actions accepted where a `HH:mm[:ss]` time can also go. */
private const val CANCEL_TOKEN = "cancel"
private const val PLAY_TOKEN = "play"
private const val PAUSE_TOKEN = "pause"

/**
 * Handles the `/display schedule` command: `this|<id> [play|pause|cancel] [<HH:mm[:ss]>]`.
 */
@PaperOnly
class ScheduleCommand : SubCommand {
    override val name = "schedule"
    override val permission = PaperServer.config.permissions.schedule
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        val token = args.getOrNull(0) ?: "this"

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return
        if (!canManage(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.scheduleOthers))) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        val localize =
            { key: String, values: List<String> -> MessageUtil.formatIndexed(player, key, *values.toTypedArray()) }
        val text = respond(data, args.getOrNull(1), args.getOrNull(2), player.uniqueId, localize)
        MessageUtil.sendColoredMessage(player, text)
    }
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display schedule` command. */
@ModLoaderOnly
object VanillaScheduleCommand {
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String, action: String?, time: String?): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0
        val canManageOthers = VanillaPermissions.has(
            player, VanillaServerState.config.permissions.scheduleOthers, VanillaPermissions.Fallback.OP,
        )
        if (!canManage(data, player.uuid, canManageOthers)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return 0
        }

        val localize =
            { key: String, values: List<String> -> MessageUtil.formatIndexed(player, key, *values.toTypedArray()) }
        val text = respond(data, action, time, player.uuid, localize)
        MessageUtil.sendColoredMessage(player, text)
        return 1
    }
}

/** Dispatches on [action] / [time]: no action shows the current schedule, `cancel` clears it, `play` / `pause` sets or prompts for a time. */
private fun respond(
    data: DisplayData,
    action: String?,
    time: String?,
    playerId: UUID,
    localize: (String, List<String>) -> String,
): String = when (action) {
    null -> showCurrent(data, playerId, localize)
    CANCEL_TOKEN -> cancel(data, localize)
    PLAY_TOKEN -> onAction(data, PlaybackAction.PLAY, time, playerId, localize)
    PAUSE_TOKEN -> onAction(data, PlaybackAction.PAUSE, time, playerId, localize)
    else -> localize("scheduleInvalidTime", emptyList())
}

/** True if [playerId] may change [data]'s schedule: its owner, or granted [canManageOthers]. */
private fun canManage(data: DisplayData, playerId: UUID, canManageOthers: Boolean): Boolean =
    data.ownerId == playerId || canManageOthers

/** Reports the current schedule, if any, converted into [viewerId]'s own local time. */
private fun showCurrent(data: DisplayData, viewerId: UUID, localize: (String, List<String>) -> String): String {
    val at = data.scheduledStart ?: return localize("scheduleNoneSet", emptyList())
    val action = data.scheduledAction ?: PlaybackAction.PLAY
    val localSecond = ScheduleTimeUtil.localSecondOfDay(at, ScheduleTimeUtil.offsetMinutesOf(viewerId))
    return localize(
        "scheduleCurrentSet",
        listOf(localize(actionKey(action), emptyList()), ScheduleTimeUtil.format(localSecond, withSeconds = true)),
    )
}

/** Cancels the pending schedule, if any. */
private fun cancel(data: DisplayData, localize: (String, List<String>) -> String): String =
    if (ScheduledPlaybackManager.cancel(data.id)) localize("scheduleCancelled", emptyList())
    else localize("scheduleNoneSet", emptyList())

/** With no [time] yet, reveals the player's current local time and prompts for one; otherwise parses and schedules it. */
private fun onAction(
    data: DisplayData,
    action: PlaybackAction,
    time: String?,
    playerId: UUID,
    localize: (String, List<String>) -> String,
): String {
    val offset = ScheduleTimeUtil.offsetMinutesOf(playerId)
    if (time == null) {
        val nowSecond = ScheduleTimeUtil.currentSecondOfDay(offset)
        return localize("scheduleNeedsTime", listOf(ScheduleTimeUtil.format(nowSecond, withSeconds = true)))
    }

    val targetSecond = ScheduleTimeUtil.parseSecondOfDay(time)
        ?: return localize("scheduleInvalidTime", emptyList())

    val now = Clock.System.now()
    val nowSecond = ScheduleTimeUtil.localSecondOfDay(now, offset)
    val secondsAhead = ScheduleTimeUtil.secondsUntil(targetSecond, offset, now)
    val target = ScheduleTimeUtil.resolveNextOccurrence(targetSecond, offset, now)

    ScheduledPlaybackManager.schedule(data, target, action)

    val countdown = when {
        secondsAhead >= 3600 -> localize(
            "scheduleCountdownHours",
            listOf((secondsAhead / 3600).toString(), ((secondsAhead % 3600) / 60).toString())
        )

        secondsAhead >= 60 -> localize(
            "scheduleCountdownMinutes",
            listOf((secondsAhead / 60).toString(), (secondsAhead % 60).toString())
        )

        else -> localize("scheduleCountdownSeconds", listOf(secondsAhead.toString()))
    }
    return localize(
        "scheduleSet",
        listOf(
            ScheduleTimeUtil.format(nowSecond, withSeconds = true),
            data.shortLabel,
            localize(actionKey(action), emptyList()),
            ScheduleTimeUtil.format(targetSecond, withSeconds = true),
            countdown,
        ),
    )
}

/** The i18n key for [action]'s localized verb phrase, used inside [scheduleSet]/[scheduleCurrentSet]. */
private fun actionKey(action: PlaybackAction): String =
    if (action == PlaybackAction.PAUSE) "scheduleActionPause" else "scheduleActionPlay"
