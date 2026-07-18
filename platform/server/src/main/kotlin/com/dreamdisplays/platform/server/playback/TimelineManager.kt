package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.PlaybackAction
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.Timeline
import com.dreamdisplays.core.protocol.PlaybackCommand
import com.dreamdisplays.core.protocol.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the authoritative playback clock for every `SYNCED` and `BROADCAST` display. Clients never
 * report their own time; they send intents ([PlaybackCommand]) and this
 * manager mutates the single [Timeline] and rebroadcasts it. Replaces the old owner-relay
 * `StateManager` for the v2 path. Watch-party timelines live in [WatchPartyManager].
 */
object TimelineManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/TimelineManager")

    /** Minimum interval between rebroadcasts of the same timeline to nearby players. */
    private const val PERIODIC_BROADCAST_MS = 2_000L

    /** Ceiling for client seeks when no duration is known, matching the v1 24h sanity limit. */
    internal const val MAX_SEEK_MS = 24L * 60 * 60 * 1_000

    /** Live platform transport, injected at startup. */
    private lateinit var transport: PlaybackTransport

    /** The current authoritative timeline for each display, if any. */
    private val timelines = ConcurrentHashMap<UUID, Timeline>()

    /** The last time a timeline was broadcast for each display, if any. */
    private val lastBroadcast = ConcurrentHashMap<UUID, Long>()

    /** Wires the platform transport and seeds timelines for already-loaded displays. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
        DisplayManager.getDisplays().forEach(::ensureTimeline)
    }

    /** The current authoritative timeline for [displayId], or null if it has no server clock. */
    fun timelineOf(displayId: UUID): Timeline? = timelines[displayId]

    /**
     * Applies a client playback intent to a `SYNCED` display. Returns true when it was permitted,
     * applied, and rebroadcast. Watch-party intents are handled by [WatchPartyManager], not here.
     */
    fun onCommand(display: DisplayData, senderId: UUID, action: PlaybackAction, positionMs: Long): Boolean {
        if (display.mode != PlaybackMode.SYNCED || WatchPartyManager.hasSession(display.id)) return false
        val ctx = PlaybackContexts.of(display, senderId, transport.isAdmin(senderId))
        if (!PlaybackPermissions.canPlayPause(ctx)) return false

        val now = transport.nowMs()
        val current = timelines[display.id] ?: Timeline.start(now)
        val updated = when (action) {
            PlaybackAction.PLAY -> current.withPaused(false, now)
            PlaybackAction.PAUSE -> current.withPaused(true, now)
            PlaybackAction.SEEK -> current.seekedTo(clampSeek(positionMs, display), now)
            PlaybackAction.RESTART -> Timeline.start(now)
        }
        timelines[display.id] = updated
        broadcast(display, updated)
        return true
    }

    /** Sends the current timeline to one player (RequestSync reply / late-join catch-up). */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        val timeline = ensureTimeline(display) ?: return
        transport.sendTo(playerId, timeline.toSync(display.id, display.mode, transport.nowMs()))
    }

    /** Re-initializes the clock after a base-mode change and broadcasts the result. */
    fun onModeChanged(display: DisplayData, positionMs: Long = -1) {
        timelines.remove(display.id)
        val now = transport.nowMs()
        val timeline = when (display.mode) {
            PlaybackMode.SYNCED -> Timeline(positionMs.coerceAtLeast(0), now, paused = false)
            PlaybackMode.BROADCAST -> Timeline(positionMs.coerceAtLeast(0), now, paused = false, loop = true)
            else -> null
        }
        if (timeline != null) {
            timelines[display.id] = timeline
            broadcast(display, timeline)
        }
    }

    /** Resets the clock to 0 when the video changes (`SYNCED` / `BROADCAST` only). */
    fun onVideoChanged(display: DisplayData) {
        if (display.mode != PlaybackMode.SYNCED && display.mode != PlaybackMode.BROADCAST) return
        val fresh = Timeline.start(transport.nowMs(), loop = display.mode == PlaybackMode.BROADCAST)
        timelines[display.id] = fresh
        broadcast(display, fresh)
    }

    /** Forgets a removed display. */
    fun remove(displayId: UUID) {
        timelines.remove(displayId)
        lastBroadcast.remove(displayId)
    }

    /** Periodic keep-alive so late joiners and drifting clients stay corrected. Called once per second. */
    fun tick() {
        if (timelines.isEmpty()) return
        val now = transport.nowMs()
        for ((displayId, timeline) in timelines) {
            if (now - (lastBroadcast[displayId] ?: 0L) < PERIODIC_BROADCAST_MS) continue
            val display = DisplayManager.getDisplayData(displayId) ?: continue
            broadcast(display, timeline)
        }
    }

    /**
     * Clamps a client seek to the known media duration when the server has one (stored in ns),
     * otherwise to [MAX_SEEK_MS], so a hostile seek can't run the shared clock off to infinity.
     */
    internal fun clampSeek(positionMs: Long, display: DisplayData): Long {
        val durationMs = display.duration?.let { it / 1_000_000L } ?: 0L
        return positionMs.coerceIn(0, if (durationMs > 0) durationMs else MAX_SEEK_MS)
    }

    /** Creates a running (`SYNCED`) or looping (`BROADCAST`) timeline if one is due, else clears it. */
    private fun ensureTimeline(display: DisplayData): Timeline? {
        if (WatchPartyManager.hasSession(display.id)) return null
        return when (display.mode) {
            PlaybackMode.SYNCED -> timelines.getOrPut(display.id) { Timeline.start(transport.nowMs()) }
            PlaybackMode.BROADCAST -> timelines.getOrPut(display.id) { Timeline.start(transport.nowMs(), loop = true) }
            else -> {
                timelines.remove(display.id); null
            }
        }
    }

    /** Stamps and broadcasts [timeline] for [display] to every nearby v2 player. */
    private fun broadcast(display: DisplayData, timeline: Timeline) {
        val now = transport.nowMs()
        lastBroadcast[display.id] = now
        transport.broadcast(display, timeline.toSync(display.id, display.mode, now))
    }
}
