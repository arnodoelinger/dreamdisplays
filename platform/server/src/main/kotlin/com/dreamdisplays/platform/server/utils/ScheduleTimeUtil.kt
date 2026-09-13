package com.dreamdisplays.platform.server.utils

import com.dreamdisplays.platform.server.utils.net.V2PlayerTracker
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Player-local wall-clock math for `/display schedule`.
 */
object ScheduleTimeUtil {
    /** `HH:mm` or `HH:mm:ss`, strict 24-hour. */
    private val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?$")

    /** Seconds in a day. */
    private const val SECONDS_PER_DAY = 86_400

    /** [uuid]'s advertised UTC offset in minutes, or 0 (UTC) if their [ClientHello] hasn't arrived yet. */
    fun offsetMinutesOf(uuid: UUID): Int = V2PlayerTracker.helloOf(uuid)?.timeZoneOffsetMinutes ?: 0

    /** Parses a strict `HH:mm` or `HH:mm:ss` [token] to a second-of-day (0..86399), or null if malformed. */
    fun parseSecondOfDay(token: String): Int? {
        val match = TIME_PATTERN.matchEntire(token) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return hours.toInt() * 3600 + minutes.toInt() * 60 + (seconds.toIntOrNull() ?: 0)
    }

    /** Formats a second-of-day (0..86399) as `HH:mm`, or `HH:mm:ss` when [withSeconds]. */
    fun format(secondOfDay: Int, withSeconds: Boolean = false): String {
        val h = secondOfDay / 3600
        val m = (secondOfDay % 3600) / 60
        val s = secondOfDay % 60
        return if (withSeconds) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(h, m)
    }

    /** The player-local second-of-day (0..86399) for [instant], given their [offsetMinutes]. */
    fun localSecondOfDay(instant: Instant, offsetMinutes: Int): Int {
        val localEpochSecond = Math.floorDiv(instant.toEpochMilliseconds(), 1_000L) + offsetMinutes * 60L
        return Math.floorMod(localEpochSecond, SECONDS_PER_DAY.toLong()).toInt()
    }

    /** The player-local second-of-day (0..86399) right now, given their [offsetMinutes]. */
    fun currentSecondOfDay(offsetMinutes: Int): Int = localSecondOfDay(Clock.System.now(), offsetMinutes)

    /** Minute-of-day (0..1439) for [secondOfDay], used by the per-minute suggestion list. */
    fun minuteOfDay(secondOfDay: Int): Int = secondOfDay / 60

    /**
     * Seconds from [now] (player-local) until [targetSecondOfDay] next occurs, in `1..86400`; a
     * target equal to the current second resolves to a full day out (already-passed semantics),
     * never `0`.
     */
    fun secondsUntil(targetSecondOfDay: Int, offsetMinutes: Int, now: Instant = Clock.System.now()): Int {
        val nowSecond = localSecondOfDay(now, offsetMinutes)
        val ahead = Math.floorMod(targetSecondOfDay - nowSecond, SECONDS_PER_DAY)
        return if (ahead == 0) SECONDS_PER_DAY else ahead
    }

    /** Resolves [targetSecondOfDay] (player-local) to the absolute [Instant] it next occurs at. */
    fun resolveNextOccurrence(targetSecondOfDay: Int, offsetMinutes: Int, now: Instant = Clock.System.now()): Instant =
        now + secondsUntil(targetSecondOfDay, offsetMinutes, now).seconds

    /** Compact, language-neutral countdown for a tab-complete tooltip, e.g. `"+45s"` / `"+4m32s"` / `"+1h05m"`. */
    fun compactCountdown(secondsAhead: Int): String {
        val hours = secondsAhead / 3600
        val minutes = (secondsAhead % 3600) / 60
        val seconds = secondsAhead % 60
        return when {
            hours > 0 -> "+%dh%02dm".format(hours, minutes)
            minutes > 0 -> "+%dm%02ds".format(minutes, seconds)
            else -> "+%ds".format(seconds)
        }
    }
}
