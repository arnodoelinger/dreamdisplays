package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.playback.model.FullscreenAckAction
import com.dreamdisplays.api.playback.model.FullscreenMode
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.playback.model.Timeline
import com.dreamdisplays.api.playback.model.FullscreenSessionRecord
import com.dreamdisplays.core.protocol.common.packets.DisplayDelete
import com.dreamdisplays.core.protocol.common.packets.FullscreenState
import com.dreamdisplays.core.protocol.common.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.storage.FullscreenSessionStore
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** A player is targeted for radius-based fullscreen sessions when within [blocks] of (x, y, z) in [world]. */
data class FullscreenRadiusTarget(val world: String, val x: Double, val y: Double, val z: Double, val blocks: Double)

/** Manages server-forced fullscreen broadcast sessions targeting players by name or radius. */
object FullscreenBroadcastManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Minimum interval between re-evaluating radius membership and refreshing already-shown targets. */
    private const val TICK_MS = 1_000L

    /** Minimum interval between drift-correction resends of a real display's timeline to a target. */
    private const val TIMELINE_RESEND_MS = 5_000L

    /** How long to wait for a delivery to be confirmed before sending it again. */
    private const val REDELIVER_UNCONFIRMED_MS = 1_500L

    /** How long a single delivery keeps being retried before the viewer is left alone. */
    private const val DELIVERY_CONFIRM_WINDOW_MS = 20_000L

    private lateinit var transport: PlaybackTransport
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val sessionId: String,
        var display: DisplayData,
        val virtual: Boolean,
        val transientSession: Boolean,
        val ownerId: UUID,
        var mode: FullscreenMode,
        var forced: Boolean,
        var volume: Float,
        var loop: Boolean,
        var quality: String,
        var title: String,
        var namedTargets: Set<UUID>?,
        var radius: FullscreenRadiusTarget?,
        var timeline: Timeline?,
        val shownTo: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val dismissedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val minimizedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val confirmedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val deliveredAtMs: MutableMap<UUID, Long> = ConcurrentHashMap(),
        val retryUntilMs: MutableMap<UUID, Long> = ConcurrentHashMap(),
        var lastTick: Long = 0,
        var lastTimelineResend: Long = 0,
    )

    /** Wires the platform transport. */
    fun init(transport: PlaybackTransport) {
        this.transport = transport
    }

    /** True if a session with [sessionId] is currently live. */
    fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /** The live session ids targeting a given real display, if any (a display can only host one). */
    fun sessionIdForDisplay(displayId: UUID): String? =
        sessions.values.firstOrNull { !it.virtual && it.display.id == displayId }?.sessionId

    /** Resolves a display by id/prefix or creates a virtual one if the string looks like a URL. */
    fun resolveOrCreateDisplay(
        idOrUrl: String,
        ownerId: UUID,
        virtualDisplayId: UUID? = null,
    ): Pair<DisplayData, Boolean>? {
        resolveDisplayByIdOrPrefix(idOrUrl)?.let { return it to false }
        if (!looksLikeUrl(idOrUrl)) return null
        val virtual = transport.createVirtualDisplay(virtualDisplayId ?: UUID.randomUUID(), ownerId) ?: return null
        virtual.url = MediaSource.from(idOrUrl).toResolvableUrl() ?: idOrUrl
        return virtual to true
    }

    /** Resolves a network fullscreen target to its URL for broadcasting. */
    fun resolveNetworkFullscreenUrl(idOrUrl: String): String? {
        displayUrlByIdOrPrefix(idOrUrl)?.let { return it }
        if (!looksLikeUrl(idOrUrl)) return null
        return MediaSource.from(idOrUrl).toResolvableUrl() ?: idOrUrl
    }

    /** Gets the video URL of a display by full id or unambiguous prefix. */
    fun displayUrlByIdOrPrefix(idOrPrefix: String): String? =
        resolveDisplayByIdOrPrefix(idOrPrefix)?.url?.takeIf(String::isNotBlank)

    /** Exact UUID match first, then an unambiguous case-insensitive id prefix (>= 4 chars). */
    private fun resolveDisplayByIdOrPrefix(idOrPrefix: String): DisplayData? =
        DisplayManager.resolveByIdOrPrefix(idOrPrefix)

    /** True if [value] looks like a URL (with scheme or domain-like format). */
    private fun looksLikeUrl(value: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(value) ||
                Regex("""^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+(?:[/?#].*)?$""")
                    .matches(value.trim())

    /** Sends the current timeline to a player if a live session owns the display. */
    fun sendCurrentTo(displayId: UUID, playerId: UUID): Boolean {
        val session = sessions.values.firstOrNull { it.display.id == displayId } ?: return false
        sendTimeline(session, playerId, transport.nowMs())
        return true
    }

    /** Display target suggestions (name if set, else the same short-id `/display list` shows) — see [shortLabel]. */
    fun displayIdSuggestions(): List<String> = DisplayManager.getDisplays().map { it.shortLabel }

    /**
     * Starts a new session. [namedTargets] and [radius] are combined by OR - at least one must be
     * given. Returns the started session's id, or null if neither targeting condition was set, a
     * session with [sessionId] already exists, or [display] already hosts a session.
     */
    fun start(
        sessionId: String,
        display: DisplayData,
        virtual: Boolean,
        transientSession: Boolean,
        ownerId: UUID,
        mode: FullscreenMode,
        forced: Boolean,
        volume: Float,
        loop: Boolean,
        quality: String,
        title: String,
        namedTargets: Set<UUID>?,
        radius: FullscreenRadiusTarget?,
        timelineAnchorMs: Long? = null,
    ): String? {
        if (namedTargets.isNullOrEmpty() && radius == null) return null
        if (sessions.containsKey(sessionId)) return null
        if (!virtual && sessionIdForDisplay(display.id) != null) return null

        val now = transport.nowMs()
        val session = Session(
            sessionId = sessionId,
            display = display,
            virtual = virtual,
            transientSession = transientSession,
            ownerId = ownerId,
            mode = mode,
            forced = forced,
            volume = volume,
            loop = loop,
            quality = quality,
            title = title,
            namedTargets = namedTargets,
            radius = radius,
            timeline = if (virtual) Timeline.start(timelineAnchorMs ?: now, loop = true) else null,
        )
        sessions[sessionId] = session
        deliverToAll(session)
        persist()
        return sessionId
    }

    /** Stops [sessionId], notifying every player it was shown to. Returns false if it doesn't exist. */
    fun stop(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        teardown(session)
        persist()
        return true
    }

    /** Stops every session hosted on [displayId] (real display only); used when the display itself is deleted. */
    fun stopByDisplay(displayId: UUID) {
        sessions.values.filter { !it.virtual && it.display.id == displayId }.forEach { stop(it.sessionId) }
    }

    /** Stops every live session, e.g. on server shutdown when persistence isn't desired for this run. */
    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
    }

    /** Snapshot of live sessions for the `list` subcommand: (sessionId, display id, title, reach count). */
    fun list(): List<FullscreenSessionInfo> = sessions.values.map { session ->
        FullscreenSessionInfo(
            session.sessionId,
            session.display.id,
            session.virtual,
            session.title,
            session.shownTo.size
        )
    }

    /**
     * Applies a client's [FullscreenAckAction] for [sessionId]. Dismissing an unforced session (Esc)
     * drops the player from its targets and means stop.
     */
    fun handleAck(sessionId: String, playerId: UUID, action: FullscreenAckAction) {
        val session = sessions[sessionId] ?: return
        when (action) {
            FullscreenAckAction.SHOWN -> {
                session.confirmedBy.add(playerId)
                session.minimizedBy.remove(playerId)
                sendTimeline(session, playerId, transport.nowMs())
                onMinimizedChanged?.invoke(session.sessionId, playerId, false)
            }

            FullscreenAckAction.DISMISSED -> if (!session.forced) {
                session.shownTo.remove(playerId)
                session.dismissedBy.add(playerId)
                if (session.shownTo.isEmpty()) stop(sessionId)
            }

            FullscreenAckAction.MINIMIZED -> {
                session.confirmedBy.add(playerId)
                session.minimizedBy.add(playerId)
                sendTimeline(session, playerId, transport.nowMs())
                onMinimizedChanged?.invoke(session.sessionId, playerId, true)
            }
        }
    }

    /**
     * Fired when a viewer collapses a session to PiP or restores it, so
     * [com.dreamdisplays.platform.server.proxy.NetworkFullscreenManager]-style listeners can track reach.
     */
    var onMinimizedChanged: ((sessionId: String, playerId: UUID, minimized: Boolean) -> Unit)? = null

    /** Forgets sessions for a player who left, so a rejoin is treated as a fresh delivery (a past dismissal doesn't carry over either). */
    fun onPlayerQuit(playerId: UUID) {
        sessions.values.forEach {
            forget(it, playerId)
            it.dismissedBy.remove(playerId)
        }
    }

    /** Drops every per-player delivery record, so a later re-target is treated as a fresh delivery. */
    private fun forget(session: Session, playerId: UUID) {
        session.shownTo.remove(playerId)
        session.confirmedBy.remove(playerId)
        session.deliveredAtMs.remove(playerId)
        session.retryUntilMs.remove(playerId)
    }

    /** Re-sends every live session's state to [playerId] on join, in case they're still within a radius/selector. */
    fun onPlayerJoin(playerId: UUID) {
        for (session in sessions.values) {
            if (isTargeted(session, playerId)) deliverTo(session, playerId)
        }
    }

    /**
     * Adds [playerId] to [sessionId]'s frozen `named targets` and delivers immediately if they're online —
     * `named` sessions don't re-scan for new targets.
     */
    fun addTarget(sessionId: String, playerId: UUID, minimized: Boolean = false): Boolean {
        val session = sessions[sessionId] ?: return false
        session.namedTargets = (session.namedTargets ?: emptySet()) + playerId
        session.dismissedBy.remove(playerId)
        if (minimized) session.minimizedBy.add(playerId) else session.minimizedBy.remove(playerId)
        if (playerId in transport.onlinePlayerIds()) deliverTo(session, playerId)
        return true
    }

    /** Forgets a removed display's session without persisting a stop-broadcast (display is already gone). */
    fun onDisplayRemoved(displayId: UUID) {
        sessions.values.filter { !it.virtual && it.display.id == displayId }.map { it.sessionId }.forEach {
            sessions.remove(it)
        }
        persist()
    }

    /** Re-evaluates radius membership and refreshes drifting clients. Called once per second. */
    fun tick() {
        if (sessions.isEmpty()) return
        val now = transport.nowMs()
        for (session in sessions.values) {
            if (now - session.lastTick < TICK_MS) continue
            session.lastTick = now
            reconcileTargets(session, now)
        }
    }

    /** Restores persisted (non-transient) sessions from disk; call once worlds/displays are loaded. */
    fun restore() {
        val records = FullscreenSessionStore.load()
        if (records.isEmpty()) return
        var restored = 0
        for (record in records) {
            val display = resolveRecordDisplay(record) ?: run {
                logger.warn("Dropping persisted fullscreen session ${record.sessionId}: display unavailable.")
                continue
            }
            val ownerId = runCatching { UUID.fromString(record.ownerId) }.getOrNull() ?: continue
            val now = transport.nowMs()
            val session = Session(
                sessionId = record.sessionId,
                display = display,
                virtual = record.virtual,
                transientSession = false,
                ownerId = ownerId,
                mode = FullscreenMode.entries.getOrElse(record.mode) { FullscreenMode.STANDARD },
                forced = record.forced,
                volume = record.volume,
                loop = record.loop,
                quality = record.quality,
                title = record.title,
                namedTargets = record.namedTargets?.map(UUID::fromString)?.toSet(),
                radius = record.radiusWorld?.let { world ->
                    FullscreenRadiusTarget(world, record.radiusX, record.radiusY, record.radiusZ, record.radiusBlocks)
                },
                timeline = if (record.virtual) Timeline.start(now, loop = true) else null,
            )
            sessions[session.sessionId] = session
            restored++
        }
        if (restored > 0) logger.info("Restored $restored persisted fullscreen session(s).")
    }

    /** Resolves the display a persisted [record] targets: looks up a real display, or rebuilds a synthetic one. */
    private fun resolveRecordDisplay(record: FullscreenSessionRecord): DisplayData? {
        val id = runCatching { UUID.fromString(record.displayId) }.getOrNull() ?: return null
        if (!record.virtual) return DisplayManager.getDisplayData(id)
        val ownerId = runCatching { UUID.fromString(record.ownerId) }.getOrNull() ?: return null
        val display = transport.createVirtualDisplay(id, ownerId) ?: return null
        display.url = record.url
        display.lang = record.lang
        return display
    }

    /** Persists every non-transient session, or clears the store when none remain. */
    private fun persist() {
        val records = sessions.values.filter { !it.transientSession }.map { session ->
            FullscreenSessionRecord(
                sessionId = session.sessionId,
                displayId = session.display.id.toString(),
                virtual = session.virtual,
                url = if (session.virtual) session.display.url else "",
                lang = if (session.virtual) session.display.lang else "",
                ownerId = session.ownerId.toString(),
                mode = session.mode.wire,
                forced = session.forced,
                volume = session.volume,
                loop = session.loop,
                quality = session.quality,
                title = session.title,
                namedTargets = session.namedTargets?.map(UUID::toString),
                radiusWorld = session.radius?.world,
                radiusX = session.radius?.x ?: 0.0,
                radiusY = session.radius?.y ?: 0.0,
                radiusZ = session.radius?.z ?: 0.0,
                radiusBlocks = session.radius?.blocks ?: 0.0,
            )
        }
        FullscreenSessionStore.save(records)
    }

    /** True if [playerId] matches [session]'s name selector or falls within its radius (OR'd). */
    private fun isTargeted(session: Session, playerId: UUID): Boolean {
        if (playerId in session.dismissedBy) return false
        if (session.namedTargets?.contains(playerId) == true) return true
        val radius = session.radius ?: return false
        val distSq = transport.playerDistanceSq(playerId, radius.world, radius.x, radius.y, radius.z) ?: return false
        return distSq <= radius.blocks * radius.blocks
    }

    /** Delivers [session] to every currently online, currently-targeted player. */
    private fun deliverToAll(session: Session) {
        for (playerId in transport.onlinePlayerIds()) {
            if (isTargeted(session, playerId)) deliverTo(session, playerId)
        }
    }

    /** Sends the display, fullscreen-state, and current playback position to one [playerId]. */
    private fun deliverTo(session: Session, playerId: UUID) {
        transport.sendDisplayInfo(playerId, session.display, session.forced)
        transport.sendTo(
            playerId,
            FullscreenState(
                sessionId = session.sessionId,
                displayId = session.display.id,
                active = true,
                mode = session.mode.wire,
                forced = session.forced,
                volume = session.volume,
                title = session.title,
                loop = session.loop,
                quality = session.quality,
                minimized = playerId in session.minimizedBy,
            ),
        )
        val now = transport.nowMs()
        sendTimeline(session, playerId, now)
        if (session.shownTo.add(playerId)) session.retryUntilMs[playerId] = now + DELIVERY_CONFIRM_WINDOW_MS
        session.confirmedBy.remove(playerId)
        session.deliveredAtMs[playerId] = now
    }

    /** Sends the session's current playback position: the display's own clock for real displays, or the session's own looping [Timeline] for virtual ones. */
    private fun sendTimeline(session: Session, playerId: UUID, now: Long) {
        val timeline = session.timeline
        if (timeline != null) {
            transport.sendTo(playerId, timeline.toSync(session.display.id, PlaybackMode.BROADCAST, now))
        } else {
            TimelineManager.sendCurrent(session.display, playerId)
        }
    }

    /** Adds newly-in-range targets, drops out-of-range ones, and periodically refreshes drifting clients still shown. */
    private fun reconcileTargets(session: Session, now: Long) {
        val resendDue = now - session.lastTimelineResend >= TIMELINE_RESEND_MS
        for (playerId in transport.onlinePlayerIds()) {
            val targeted = isTargeted(session, playerId)
            val shown = playerId in session.shownTo
            if (targeted && !shown) {
                deliverTo(session, playerId)
            } else if (!targeted && shown) {
                transport.sendTo(playerId, FullscreenState(sessionId = session.sessionId, active = false))
                forget(session, playerId)
            } else if (targeted) {
                val unconfirmed = playerId !in session.confirmedBy &&
                        now < (session.retryUntilMs[playerId] ?: 0L) &&
                        now - (session.deliveredAtMs[playerId] ?: now) >= REDELIVER_UNCONFIRMED_MS
                if (unconfirmed) deliverTo(session, playerId) else if (resendDue) sendTimeline(session, playerId, now)
            }
        }
        if (resendDue) session.lastTimelineResend = now
    }

    /** Tells everyone the session was shown to that it ended, and cleans up virtual display remnants. */
    private fun teardown(session: Session) {
        val now = transport.nowMs()
        for (playerId in session.shownTo) {
            transport.sendTo(playerId, FullscreenState(sessionId = session.sessionId, active = false))
            if (session.virtual) transport.sendTo(playerId, DisplayDelete(session.display.id))
        }
        logger.info("Stopped fullscreen session ${session.sessionId} (reached ${session.shownTo.size} player(s)) at $now.")
    }
}

/** Snapshot of a live fullscreen session for the `list` subcommand. */
data class FullscreenSessionInfo(
    val sessionId: String,
    val displayId: UUID,
    val virtual: Boolean,
    val title: String,
    val reach: Int
)
