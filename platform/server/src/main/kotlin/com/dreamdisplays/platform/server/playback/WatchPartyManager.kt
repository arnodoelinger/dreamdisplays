package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.api.playback.model.WatchPartyAction
import com.dreamdisplays.api.playback.model.WatchPartySessionState
import com.dreamdisplays.api.playback.model.WatchPartySessionState.*
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.WatchPartyState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.ActionThrottle
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.proxy.TransferTracker
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Runs the ephemeral watch-party state machine, one session per display. Only the host drives playback; nearby players just receive updates. */
// TODO: release in 1.10.0
object WatchPartyManager {
    private const val COUNTDOWN_MS = 3_000L
    private const val HOST_GRACE_MS = 30_000L
    private const val PERIODIC_BROADCAST_MS = 1_000L

    private val controlThrottle = ActionThrottle()
    private const val CONTROL_COOLDOWN_MS = 100L

    private lateinit var transport: PlaybackTransport
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** Fired whenever a `virtual` (network) session's state changes, with the up-to-date snapshot — the seam the proxy uses to fan updates out network-wide. */
    var onVirtualBroadcast: ((displayId: UUID, snapshot: WatchPartyState) -> Unit)? = null

    /** Fired when a `virtual` session is closed, with its `sessionId` (the network party id) - the seam that lets [com.dreamdisplays.platform.server.proxy.ProxyBridge] fan a `CloseNetworkWatchParty` out. */
    var onVirtualClosed: ((partyId: String) -> Unit)? = null

    private class Session(
        val displayId: UUID,
        val sessionId: String,
        var hostId: UUID,
        var url: String,
        var lang: String,
        var state: WatchPartySessionState,
        var timeline: Timeline,
        val virtual: Boolean = false,
        val display: DisplayData? = null,
        val members: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
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

    /** Starts a session on [display] with [hostId] as host. Returns false if a session already exists or the player lacks permission. */
    fun start(display: DisplayData, hostId: UUID, url: String, lang: String, virtual: Boolean = false): Boolean {
        if (hasSession(display.id)) return false
        val ctx = PlaybackContexts.of(
            display, hostId, transport.isAdmin(hostId),
            { transport.isTerritoryMember(display, hostId) },
        )
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
            virtual = virtual,
            display = if (virtual) display else null,
            members = if (virtual) mutableSetOf(hostId) else ConcurrentHashMap.newKeySet(),
        )
        if (sessions.putIfAbsent(display.id, session) != null) return false
        if (!virtual) TimelineManager.remove(display.id)
        broadcast(session, now)
        return true
    }

    /** Adds [playerId] as a member of the `virtual` network party on [displayId] (the host's own backend session only). */
    fun addMember(displayId: UUID, playerId: UUID): Boolean {
        val session = sessions[displayId] ?: return false
        if (!session.virtual) return false
        session.members.add(playerId)
        if (playerId in transport.onlinePlayerIds()) {
            session.display?.let { transport.sendDisplayInfo(playerId, it, forced = false) }
            transport.sendTo(playerId, snapshot(session, transport.nowMs()))
        }
        return true
    }

    /**
     * Applies a participant or host control. `READY` / `UNREADY` are open to any nearby player; every
     * other action requires the host. Returns true when the control was applied and rebroadcast.
     */
    fun control(display: DisplayData, senderId: UUID, action: WatchPartyAction, positionMs: Long): Boolean {
        val session = sessions[display.id] ?: return false
        val now = transport.nowMs()

        if (action == WatchPartyAction.CLOSE) {
            val ctx = PlaybackContexts.of(
                display, senderId, transport.isAdmin(senderId),
                { transport.isTerritoryMember(display, senderId) },
            )
            if (!PlaybackPermissions.canCloseWatchParty(ctx)) return false
            close(display)
            return true
        }
        if (!controlThrottle.tryAcquire(senderId, CONTROL_COOLDOWN_MS)) return false

        if (action.isParticipantAction) {
            if (senderId !in nearbyIds(session)) return false
            if (action == WatchPartyAction.READY) {
                session.ready.add(senderId)
                // The host signaling ready while preparing opens the ready-check
                if (session.state == PREPARING && senderId == session.hostId) session.state = WAITING
            } else {
                session.ready.remove(senderId)
            }
            broadcast(session, now)
            return true
        }

        // All remaining controls are host-only, and the host must still be nearby
        if (senderId != session.hostId) return false
        if (senderId !in nearbyIds(session)) return false
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
                session.timeline = session.timeline.seekedTo(TimelineManager.clampSeek(positionMs, display), now)
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

    /** Whether [displayId] hosts a `virtual` (network-party host side) session. */
    fun isVirtual(displayId: UUID): Boolean = sessions[displayId]?.virtual == true

    /** Starts the host side of a network watch party: creates the shared virtual display for [displayId] and starts the local session on it. */
    fun startVirtual(displayId: UUID, hostId: UUID, url: String, lang: String): Boolean {
        val display = transport.createVirtualDisplay(displayId, hostId) ?: return false
        return start(display, hostId, url, lang, virtual = true)
    }

    /** Closes the host-side network party session identified by [partyId] (the manager's own `sessionId`), if one is live here. */
    fun closeVirtual(partyId: String): Boolean {
        val entry = sessions.entries.firstOrNull { it.value.virtual && it.value.sessionId == partyId } ?: return false
        close(entry.value.display ?: return false)
        return true
    }

    /** Creates (or, if already made, returns) the local virtual display a follower backend needs for a network party. */
    fun createFollowerDisplay(displayId: UUID, hostId: UUID): DisplayData? =
        transport.createVirtualDisplay(displayId, hostId)

    /** Delivers `DisplayInfo` for a follower's virtual [display] to one [playerId]. */
    fun sendFollowerDisplayInfo(display: DisplayData, playerId: UUID) {
        transport.sendDisplayInfo(playerId, display, forced = false)
    }

    /** Forwards an already-built wire packet straight to [playerId] — used by the follower relay to pass through relayed state and teardown. */
    fun sendToMember(playerId: UUID, packet: com.dreamdisplays.core.protocol.common.packets.DreamPacket) {
        transport.sendTo(playerId, packet)
    }

    /** Starts the host grace timer when the host disconnects; pauses a live timeline meanwhile. */
    fun onPlayerQuit(playerId: UUID) {
        // A proxy switch fires this backend's own quit event too, don't start the host-disconnect
        // grace timer for a host who's merely transferring (matters once cross-server watch party
        // sessions exist to actually follow them; a no-op guard on today's single-backend sessions).
        if (TransferTracker.isTransferring(playerId)) return
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

    /**
     * Clears the host grace timer when the host rejoins within it, so [tick] doesn't force-end the
     * session out from under a host who reconnected and is simply watching (no host action needed to
     * "prove" they're back). The session stays paused from the disconnect; the host resumes explicitly.
     */
    fun onPlayerJoin(playerId: UUID) {
        val now = transport.nowMs()
        sessions.values.filter { it.hostId == playerId && it.hostDisconnectedAt > 0L }.forEach { session ->
            session.hostDisconnectedAt = 0
            broadcast(session, now)
        }
    }

    /** Ends and removes the session on [display], handing the display back to its base mode. */
    fun close(display: DisplayData) {
        val session = sessions.remove(display.id) ?: return
        // Clearing the session: empty sessionId tells clients to drop it and revert to the base mode
        val closing = snapshot(session, transport.nowMs()).copy(sessionId = "", state = ENDED.wire)
        if (session.virtual) {
            session.members.forEach { memberId ->
                transport.sendTo(memberId, closing)
                transport.sendTo(memberId, DisplayDelete(display.id))
            }
            onVirtualClosed?.invoke(session.sessionId)
        } else {
            transport.broadcast(display, closing)
            TimelineManager.onModeChanged(display)
        }
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

    /** Players currently "nearby" the session: for a `virtual` network party, the proxy-assigned [Session.members] filtered to who's online. */
    private fun nearbyIds(session: Session): List<UUID> {
        if (session.virtual) return session.members.filter { it in transport.onlinePlayerIds() }
        val display = DisplayManager.getDisplayData(session.displayId) ?: return emptyList()
        return transport.nearbyPlayerIds(display)
    }

    private fun broadcast(session: Session, now: Long) {
        session.lastBroadcast = now
        val snap = snapshot(session, now)
        if (session.virtual) {
            nearbyIds(session).forEach { transport.sendTo(it, snap) }
            onVirtualBroadcast?.invoke(session.displayId, snap)
        } else {
            val display = DisplayManager.getDisplayData(session.displayId) ?: return
            transport.broadcast(display, snap)
        }
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
