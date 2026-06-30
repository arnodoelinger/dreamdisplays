package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.PlaybackPermissions
import com.dreamdisplays.api.playback.Timeline
import com.dreamdisplays.api.playback.WatchPartyAction
import com.dreamdisplays.api.playback.WatchPartySessionState
import com.dreamdisplays.api.playback.WatchPartySessionState.COUNTDOWN
import com.dreamdisplays.api.playback.WatchPartySessionState.CREATED
import com.dreamdisplays.api.playback.WatchPartySessionState.ENDED
import com.dreamdisplays.api.playback.WatchPartySessionState.PAUSED
import com.dreamdisplays.api.playback.WatchPartySessionState.PLAYING
import com.dreamdisplays.api.playback.WatchPartySessionState.PREPARING
import com.dreamdisplays.api.playback.WatchPartySessionState.WAITING
import com.dreamdisplays.core.protocol.WatchPartyState
import com.dreamdisplays.platform.server.datatypes.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the ephemeral watch-party state machine, one session per display. Only the host drives
 * playback; nearby participants may only mark themselves ready. The display is forced-locked for
 * the whole session (even admins can't unlock), and the session freezes at [ENDED] until the host
 * or owner closes it. All wire traffic is the [WatchPartyState] snapshot, rebroadcast on every
 * transition and once per second while live.
 */
object WatchPartyManager {
    private const val COUNTDOWN_MS = 3_000L
    private const val HOST_GRACE_MS = 30_000L
    private const val PERIODIC_BROADCAST_MS = 1_000L

    private lateinit var transport: PlaybackTransport
    private val sessions = ConcurrentHashMap<UUID, Session>()

    private class Session(
        val displayId: UUID,
        val sessionId: String,
        var hostId: UUID,
        var url: String,
        var lang: String,
        var state: WatchPartySessionState,
        var timeline: Timeline,
        val ready: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        var countdownStartEpochMs: Long = 0,
        var hostDisconnectedAt: Long = 0,
        var lastBroadcast: Long = 0,
    )

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** True if a session is currently live on [displayId]. */
    fun hasSession(displayId: UUID): Boolean = sessions.containsKey(displayId)

    /** True if [playerId] hosts the live session on [displayId]. */
    fun isHost(displayId: UUID, playerId: UUID): Boolean = sessions[displayId]?.hostId == playerId

    /**
     * Starts a session on [display] with [hostId] as host. Returns false if a session already
     * exists or the player isn't allowed to start one (locked display and not owner / admin).
     */
    fun start(display: DisplayData, hostId: UUID, url: String, lang: String): Boolean {
        if (hasSession(display.id)) return false
        val ctx = PlaybackContexts.of(display, hostId, transport.isAdmin(hostId))
        if (!PlaybackPermissions.canStartWatchParty(ctx)) return false

        val now = transport.nowMs()
        val session = Session(
            displayId = display.id,
            sessionId = UUID.randomUUID().toString().take(8),
            hostId = hostId,
            url = url,
            lang = lang,
            state = CREATED,
            timeline = Timeline.start(now, paused = true),
        )
        sessions[display.id] = session
        // The base-mode clock must stop driving the display while the party owns it
        TimelineManager.remove(display.id)
        broadcast(session, now)
        return true
    }

    /**
     * Applies a participant or host control. `READY` / `UNREADY` are open to any nearby player; every
     * other action requires the host. Returns true when the control was applied and rebroadcast.
     */
    fun control(display: DisplayData, senderId: UUID, action: WatchPartyAction, positionMs: Long): Boolean {
        val session = sessions[display.id] ?: return false
        val now = transport.nowMs()

        if (action.isParticipantAction) {
            if (senderId !in nearbyIds(session)) return false
            if (action == WatchPartyAction.READY) {
                session.ready.add(senderId)
                // The host signalling ready while preparing opens the ready-check
                if (session.state == PREPARING && senderId == session.hostId) session.state = WAITING
            } else {
                session.ready.remove(senderId)
            }
            broadcast(session, now)
            return true
        }

        if (action == WatchPartyAction.CLOSE) {
            val ctx = PlaybackContexts.of(display, senderId, transport.isAdmin(senderId))
            if (!PlaybackPermissions.canCloseWatchParty(ctx)) return false
            close(display)
            return true
        }

        // All remaining controls are host-only
        if (senderId != session.hostId) return false
        session.hostDisconnectedAt = 0 // Host is clearly present

        when (action) {
            WatchPartyAction.BEGIN -> if (session.state == WAITING) {
                session.state = COUNTDOWN
                session.countdownStartEpochMs = now + COUNTDOWN_MS
            }

            WatchPartyAction.PAUSE -> if (session.state == PLAYING) {
                session.state = PAUSED
                session.timeline = session.timeline.withPaused(true, now)
            }

            WatchPartyAction.RESUME -> if (session.state == PAUSED) {
                session.state = PLAYING
                session.timeline = session.timeline.withPaused(false, now)
            }

            WatchPartyAction.SEEK -> if (session.state == PLAYING || session.state == PAUSED) {
                session.timeline = session.timeline.seekedTo(positionMs, now)
            }

            WatchPartyAction.END -> {
                session.state = ENDED
                session.timeline = session.timeline.withPaused(true, now)
            }

            WatchPartyAction.RESTART -> if (session.state == ENDED) {
                session.state = PREPARING
                session.ready.clear()
                session.countdownStartEpochMs = 0
                session.timeline = Timeline.start(now, paused = true)
            }

            else -> return false
        }
        broadcast(session, now)
        return true
    }

    /** Sends the current session snapshot to one player (late-join catch-up). */
    fun sendCurrent(display: DisplayData, playerId: UUID) {
        val session = sessions[display.id] ?: return
        transport.sendTo(playerId, snapshot(session, transport.nowMs()))
    }

    /** Starts the host grace timer when the host disconnects; pauses a live timeline meanwhile. */
    fun onPlayerQuit(playerId: UUID) {
        val now = transport.nowMs()
        sessions.values.filter { it.hostId == playerId && it.hostDisconnectedAt == 0L }.forEach { session ->
            session.hostDisconnectedAt = now
            if (session.state == PLAYING) {
                session.state = PAUSED
                session.timeline = session.timeline.withPaused(true, now)
            }
            broadcast(session, now)
        }
    }

    /** Ends and removes the session on [display], handing the display back to its base mode. */
    fun close(display: DisplayData) {
        val session = sessions.remove(display.id) ?: return
        // Clearing the session: empty sessionId tells clients to drop it and revert to the base mode
        transport.broadcast(display, snapshot(session, transport.nowMs()).copy(sessionId = "", state = ENDED.wire))
        TimelineManager.onModeChanged(display)
    }

    /** Forgets a session when its display is deleted. */
    fun remove(displayId: UUID) {
        sessions.remove(displayId)
    }

    /** Resolves countdowns, expires dead hosts, and refreshes live sessions. Called once per second. */
    fun tick() {
        if (sessions.isEmpty()) return
        val now = transport.nowMs()
        for (session in sessions.values) {
            var changed = false
            when {
                session.state == CREATED -> {
                    session.state = PREPARING; changed = true
                }

                session.state == COUNTDOWN && now >= session.countdownStartEpochMs -> {
                    session.state = PLAYING
                    // Anchor at the shared start instant so every client's local countdown lines up
                    session.timeline = Timeline(0, session.countdownStartEpochMs, paused = false)
                    changed = true
                }

                session.hostDisconnectedAt > 0 && now - session.hostDisconnectedAt > HOST_GRACE_MS
                        && session.state != ENDED -> {
                    session.state = ENDED
                    session.timeline = session.timeline.withPaused(true, now)
                    changed = true
                }
            }
            if (changed || now - session.lastBroadcast >= PERIODIC_BROADCAST_MS) broadcast(session, now)
        }
    }

    /** Players currently in range of the session's display. */
    private fun nearbyIds(session: Session): List<UUID> {
        val display = DisplayManager.getDisplayData(session.displayId) ?: return emptyList()
        return transport.nearbyPlayerIds(display)
    }

    private fun broadcast(session: Session, now: Long) {
        val display = DisplayManager.getDisplayData(session.displayId) ?: return
        session.lastBroadcast = now
        transport.broadcast(display, snapshot(session, now))
    }

    private fun snapshot(session: Session, now: Long): WatchPartyState {
        val nearby = nearbyIds(session)
        return WatchPartyState(
            id = session.displayId,
            sessionId = session.sessionId,
            state = session.state.wire,
            hostId = session.hostId,
            hostName = transport.playerName(session.hostId) ?: "",
            url = session.url,
            lang = session.lang,
            readyCount = session.ready.count { it in nearby },
            nearbyCount = nearby.size,
            countdownStartEpochMs = session.countdownStartEpochMs,
            positionMs = session.timeline.positionAt(now),
            serverTimeMs = now,
            durationMs = 0,
            paused = session.state != PLAYING || session.timeline.paused,
        )
    }
}
