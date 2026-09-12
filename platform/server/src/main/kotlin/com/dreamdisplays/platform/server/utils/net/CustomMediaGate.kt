package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.api.security.policy.CustomMediaPolicy
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place that decides whether a player may point a display at a custom link, shared by
 * the `Paper` and vanilla `setVideo` paths so the two can never drift apart on what is allowed.
 */
internal object CustomMediaGate {
    /** Custom links are switched off for this server. */
    const val KEY_DISABLED = "customMediaDisabled"

    /** The link's host is not allowed here. */
    const val KEY_HOST = "customMediaHostBlocked"

    /** The player lacks the custom-media permission node. */
    const val KEY_PERMISSION = "customMediaNoPermission"

    /** The URL is custom but carries no usable host. */
    const val KEY_INVALID = "invalidURL"

    /** The player is aiming displays at custom links faster than [CUSTOM_LINKS_PER_WINDOW] allows. */
    const val KEY_TOO_FAST = "customMediaTooFast"

    /**
     * Custom links one player may apply per [WINDOW_MILLIS]. Every viewer of every display fetches
     * whatever is pointed at, so a player cycling displays through addresses is aiming the whole
     * server's clients at them. Far above pasting links by hand, far below a useful weapon. The
     * existing per-display cooldown does not cover this: it is one display's clock, and an attacker
     * has as many displays as the world holds.
     */
    private const val CUSTOM_LINKS_PER_WINDOW = 8

    /** Length of the sliding window [CUSTOM_LINKS_PER_WINDOW] is counted over. */
    private const val WINDOW_MILLIS = 60_000L

    /** Players tracked at once; a full map is dropped rather than grown without bound. */
    private const val MAX_TRACKED_PLAYERS = 1024

    /** When each tracked player last applied custom links, newest last. */
    private val recentCustomLinks = ConcurrentHashMap<UUID, ArrayDeque<Long>>()

    /**
     * Returns the message key explaining why [url] is refused, or null when it may be applied.
     * Passing [playerId] also counts the link against that player's rate limit, so callers that
     * only ask hypothetically (validation, previews) can leave it out.
     */
    fun refusalKey(
        url: String,
        settings: CustomMediaPolicy.Settings,
        hasPermission: Boolean,
        playerId: UUID? = null,
    ): String? {
        val verdict = when (CustomMediaPolicy.evaluate(url, settings)) {
            CustomMediaPolicy.Verdict.ALLOWED ->
                if (!hasPermission && CustomMediaPolicy.isCustom(url)) KEY_PERMISSION else null

            CustomMediaPolicy.Verdict.DISABLED -> KEY_DISABLED
            CustomMediaPolicy.Verdict.HOST_BLOCKED, CustomMediaPolicy.Verdict.HOST_NOT_ALLOWED -> KEY_HOST
            CustomMediaPolicy.Verdict.MALFORMED -> KEY_INVALID
        }
        if (verdict != null) return verdict
        if (playerId == null || !CustomMediaPolicy.isCustom(url)) return null
        return if (recordAndCheck(playerId, System.currentTimeMillis())) null else KEY_TOO_FAST
    }

    /** Records one custom link for [playerId] at [nowMillis]; false once the window is full. */
    internal fun recordAndCheck(playerId: UUID, nowMillis: Long): Boolean {
        if (recentCustomLinks.size > MAX_TRACKED_PLAYERS) recentCustomLinks.clear()
        val stamps = recentCustomLinks.computeIfAbsent(playerId) { ArrayDeque() }
        synchronized(stamps) {
            while (stamps.isNotEmpty() && nowMillis - stamps.first() >= WINDOW_MILLIS) stamps.removeFirst()
            if (stamps.size >= CUSTOM_LINKS_PER_WINDOW) return false
            stamps.addLast(nowMillis)
            return true
        }
    }

    /** Drops what is remembered about [playerId]; call when they leave. */
    fun forget(playerId: UUID) {
        recentCustomLinks.remove(playerId)
    }
}
