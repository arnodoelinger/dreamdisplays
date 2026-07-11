package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.api.playback.FullscreenAckAction
import com.dreamdisplays.api.playback.FullscreenMode
import com.dreamdisplays.api.playback.PlaybackMode
import com.dreamdisplays.api.playback.Timeline
import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.core.protocol.DisplayDelete
import com.dreamdisplays.core.protocol.FullscreenState
import com.dreamdisplays.core.protocol.toSync
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.api.storage.FullscreenSessionRecord
import com.dreamdisplays.platform.server.storage.FullscreenSessionStore
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A player is targeted for radius-based fullscreen sessions when within [blocks] of (x, y, z) in [world]. */
data class FullscreenRadiusTarget(val world: String, val x: Double, val y: Double, val z: Double, val blocks: Double)

/**
 * Runs server-forced fullscreen broadcast sessions: an admin pushes a display (world-anchored or a
 * synthetic URL-only "virtual" one) to a set of players, chosen by name selector, by radius from a
 * point, or both (OR'd together). Real-display sessions ride the display's own `SYNCED` / `BROADCAST`
 * clock ([TimelineManager]); virtual sessions have no backing display, so this manager owns their
 * looping [Timeline] directly, mirroring [WatchPartyManager]'s owned-clock pattern.
 *
 * Non-`transient` sessions persist across restarts via [FullscreenSessionStore]; virtual sessions
 * are recreated as fresh synthetic displays on [restore], real ones are dropped if their display no
 * longer exists.
 */
object FullscreenBroadcastManager {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/FullscreenBroadcastManager")

    /** Minimum interval between re-evaluating radius membership and refreshing already-shown targets. */
    private const val TICK_MS = 1_000L

    /** Minimum interval between drift-correction resends of a real display's timeline to a target. */
    private const val TIMELINE_RESEND_MS = 5_000L

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
        /** Players who closed an unforced broadcast themselves; excluded from re-delivery for the rest of this session. */
        val dismissedBy: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
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

    /**
     * Resolves the `/display fullscreen start` target argument: a full display id, an unambiguous id
     * prefix (matching `/display list`'s 8-char short id column), or only when [idOrUrl] looks like
     * a URL - a synthetic virtual display for [ownerId] backed by it. The URL is normalized the same
     * way `/display video` normalizes its argument ([MediaSource.from]), so `youtu.be/xyz` or a bare
     * `youtube.com/watch?v=...` (no scheme) work exactly like they do there. Returns null when
     * nothing matches a display and the input isn't URL-shaped (never silently treats a mistyped id
     * as a URL), or when a virtual display is needed but no world is loaded yet.
     */
    fun resolveOrCreateDisplay(idOrUrl: String, ownerId: UUID): Pair<DisplayData, Boolean>? {
        resolveDisplayByIdOrPrefix(idOrUrl)?.let { return it to false }
        if (!looksLikeUrl(idOrUrl)) return null
        val virtual = transport.createVirtualDisplay(UUID.randomUUID(), ownerId) ?: return null
        virtual.url = MediaSource.from(idOrUrl).toResolvableUrl() ?: idOrUrl
        return virtual to true
    }

    /** Exact UUID match first, then an unambiguous case-insensitive id prefix (>= 4 chars). */
    private fun resolveDisplayByIdOrPrefix(idOrPrefix: String): DisplayData? {
        runCatching { UUID.fromString(idOrPrefix) }.getOrNull()?.let { exact ->
            DisplayManager.getDisplayData(exact)?.let { return it }
        }
        if (idOrPrefix.length < 4) return null
        val matches = DisplayManager.getDisplays().filter { it.id.toString().startsWith(idOrPrefix, ignoreCase = true) }
        return matches.singleOrNull()
    }

    /**
     * True if [value] looks like a URL / link a viewer would paste — an absolute URL (`scheme://...`)
     * or a bare domain (`youtu.be/xyz`, `youtube.com/watch?v=...`, no scheme), so a failed id lookup
     * falls back to a virtual display instead of erroring. Deliberately excludes plain words/ids
     * (no dot), so a mistyped id is never silently treated as a URL.
     */
    private fun looksLikeUrl(value: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(value) ||
            Regex("""^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)+(?:[/?#].*)?$""")
                .matches(value.trim())

    /** Display id short-id suggestions (the same 8-char prefix `/display list` shows) for the `target` argument. */
    fun displayIdSuggestions(): List<String> = DisplayManager.getDisplays().map { it.id.toString().take(8) }

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
            timeline = if (virtual) Timeline.start(now, loop = true) else null,
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
        FullscreenSessionInfo(session.sessionId, session.display.id, session.virtual, session.title, session.shownTo.size)
    }

    /**
     * Applies a client's [FullscreenAckAction] for [sessionId]. Dismissing an unforced session drops
     * the player from delivery and marks them dismissed so the next [tick] doesn't immediately
     * re-deliver it to them — they're still in `namedTargets` / still in radius, so without this a
     * dismiss would just be re-shown a second later.
     */
    fun handleAck(sessionId: String, playerId: UUID, action: FullscreenAckAction) {
        val session = sessions[sessionId] ?: return
        if (action == FullscreenAckAction.DISMISSED && !session.forced) {
            session.shownTo.remove(playerId)
            session.dismissedBy.add(playerId)
        }
    }

    /** Forgets sessions for a player who left, so a rejoin is treated as a fresh delivery (a past dismissal doesn't carry over either). */
    fun onPlayerQuit(playerId: UUID) {
        sessions.values.forEach {
            it.shownTo.remove(playerId)
            it.dismissedBy.remove(playerId)
        }
    }

    /** Re-sends every live session's state to [playerId] on join, in case they're still within a radius/selector. */
    fun onPlayerJoin(playerId: UUID) {
        for (session in sessions.values) {
            if (isTargeted(session, playerId)) deliverTo(session, playerId)
        }
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
            ),
        )
        sendTimeline(session, playerId, transport.nowMs())
        session.shownTo.add(playerId)
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
                session.shownTo.remove(playerId)
            } else if (targeted && resendDue) {
                sendTimeline(session, playerId, now)
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
data class FullscreenSessionInfo(val sessionId: String, val displayId: UUID, val virtual: Boolean, val title: String, val reach: Int)
