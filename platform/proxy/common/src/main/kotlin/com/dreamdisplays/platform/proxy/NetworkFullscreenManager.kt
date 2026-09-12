package com.dreamdisplays.platform.proxy

import com.dreamdisplays.core.protocol.proxy.packets.ApplyFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.NetworkSessionInfo
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkFullscreen
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy-side authority for network-wide fullscreen broadcasts: mints the shared `sessionId` and anchor instant, and
 * tracks which sessions apply where.
 */
object NetworkFullscreenManager {
    /**
     * How far into the future (from the moment the proxy fans a broadcast out) the shared timeline
     * anchor is set. Must comfortably exceed plugin-message fan-out latency plus each backend's
     * media prebuffer, or slower backends would start visibly behind rather than synchronized.
     */
    private const val FANOUT_LEAD_MS = 1_500L

    /**
     * How stale a session may get (age since [Session.createdAtMs], with zero reach reported on
     * every backend it was ever applied to) before [pruneStale] drops it.
     */
    const val STALE_SESSION_MAX_AGE_MS: Long = 30L * 60L * 1000L

    /** One live network fullscreen broadcast. */
    class Session(
        val sessionId: String,
        val sharedDisplayId: UUID,
        val scope: String,
        val ownerId: String,
        val url: String,
        val mode: Int,
        val forced: Boolean,
        val volume: Float,
        val loop: Boolean,
        val quality: String,
        val title: String,
        val targetsRaw: String,
        val anchorProxyMs: Long,
        val createdAtMs: Long,
    ) {
        /** Backend name -> last reported reach (player count actually targeted there). */
        val reach: MutableMap<String, Int> = ConcurrentHashMap()

        /** Backends this session is still owed an [ApplyFullscreen] retry for (empty when last tried, or never acked). */
        val pendingServers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Starts a new network session from a backend's forwarded [request], anchored [FANOUT_LEAD_MS] into the future. */
    fun start(request: StartNetworkFullscreen, proxyNowMs: Long): Session {
        val session = Session(
            sessionId = generateSessionId(),
            sharedDisplayId = UUID.randomUUID(),
            scope = request.scope,
            ownerId = request.ownerId,
            url = request.url,
            mode = request.mode,
            forced = request.forced,
            volume = request.volume,
            loop = request.loop,
            quality = request.quality,
            title = request.title,
            targetsRaw = request.targetsRaw,
            anchorProxyMs = proxyNowMs + FANOUT_LEAD_MS,
            createdAtMs = proxyNowMs,
        )
        sessions[session.sessionId] = session
        return session
    }

    /** Builds the [ApplyFullscreen] every target backend receives for [session]. */
    fun toApplyPacket(session: Session): ApplyFullscreen = ApplyFullscreen(
        sessionId = session.sessionId,
        sharedDisplayId = session.sharedDisplayId.toString(),
        anchorProxyMs = session.anchorProxyMs,
        ownerId = session.ownerId,
        url = session.url,
        mode = session.mode,
        forced = session.forced,
        volume = session.volume,
        loop = session.loop,
        quality = session.quality,
        title = session.title,
        targetsRaw = session.targetsRaw,
    )

    /** Resolves a `server` flag's [scope] ("global", or one backend name) against [knownServers]. */
    fun targetServers(scope: String, knownServers: Set<String>): Set<String> =
        if (scope.equals("global", ignoreCase = true)) knownServers
        else knownServers.filterTo(mutableSetOf()) { it.equals(scope, ignoreCase = true) }

    /** Marks [servers] as owed a retry of [sessionId]'s [ApplyFullscreen] (called right after fan-out). */
    fun markPending(sessionId: String, servers: Set<String>) {
        sessions[sessionId]?.pendingServers?.addAll(servers)
    }

    /** Records a backend's [NetworkFullscreenAck][com.dreamdisplays.core.protocol.proxy.packets.NetworkFullscreenAck]. */
    fun onAck(sessionId: String, serverName: String, reach: Int, pending: Boolean) {
        val session = sessions[sessionId] ?: return
        session.reach[serverName] = reach
        if (pending) session.pendingServers.add(serverName) else session.pendingServers.remove(serverName)
    }

    /** Live sessions still owed a retry on [serverName] — called on a [PlayerReady][com.dreamdisplays.core.protocol.proxy.packets.PlayerReady]. */
    fun pendingSessionsFor(serverName: String): List<Session> =
        sessions.values.filter { serverName in it.pendingServers }

    /** Live sessionns that should apply on [serverName] right now. */
    fun liveSessionsApplicableTo(serverName: String, knownServers: Set<String>): List<Session> =
        sessions.values.filter { serverName in targetServers(it.scope, knownServers) }

    /**
     * Ids of every live session that should apply on [serverName] right now — `global`-scope sessions, plus any scoped
     * to this server specifically.
     */
    fun sessionIdsApplicableTo(serverName: String, knownServers: Set<String>): List<String> =
        sessions.values.filter { serverName in targetServers(it.scope, knownServers) }.map { it.sessionId }

    /**
     * Viewers who collapsed a session to PiP, as `sessionId -> player ids`. Lives here rather than on
     * a backend because only the backend a viewer happened to be on sees the client's ack, and it
     * drops the player the moment they switch away - the whole point is surviving that switch.
     */
    private val minimizedBy = ConcurrentHashMap<String, MutableSet<String>>()

    /** Records that [playerId] collapsed [sessionId] to PiP, or restored it. */
    fun setMinimized(sessionId: String, playerId: String, minimized: Boolean) {
        val players = minimizedBy.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        if (minimized) players.add(playerId) else players.remove(playerId)
    }

    /** The subset of [sessionIds] that [playerId] currently has collapsed to PiP. */
    fun minimizedSessionIdsFor(playerId: String, sessionIds: List<String>): List<String> =
        sessionIds.filter { minimizedBy[it]?.contains(playerId) == true }

    /** Stops and forgets [sessionId]; returns the removed session, or null if it wasn't live. */
    fun stop(sessionId: String): Session? {
        minimizedBy.remove(sessionId)
        return sessions.remove(sessionId)
    }

    /**
     * Drops every session older than [STALE_SESSION_MAX_AGE_MS] with zero reach recorded on every
     * backend it was ever applied to; returns the dropped ids. Called periodically by the `Velocity` /
     * `Bungeecord` adapter's own scheduler — this class does no scheduling of its own.
     */
    fun pruneStale(nowMs: Long): List<String> {
        val stale = sessions.values
            .filter { nowMs - it.createdAtMs >= STALE_SESSION_MAX_AGE_MS && it.reach.values.sum() == 0 }
            .map { it.sessionId }
        stale.forEach(::stop)
        return stale
    }

    /** Every live network session, for `/display fullscreen list`. */
    fun list(): List<NetworkSessionInfo> = sessions.values.map {
        NetworkSessionInfo(it.sessionId, it.scope, it.url, it.reach.values.sum())
    }

    /** Whether [sessionId] is a known, currently-live network session. */
    fun isLive(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** Same 8-character shape as a locally-minted session id, so `stop` suggestions stay uniform. */
    private fun generateSessionId(): String {
        var id: String
        do id = UUID.randomUUID().toString().take(8) while (sessions.containsKey(id))
        return id
    }
}
