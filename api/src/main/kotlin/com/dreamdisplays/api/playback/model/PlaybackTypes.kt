package com.dreamdisplays.api.playback.model

import com.dreamdisplays.api.Unstable
import kotlinx.serialization.Serializable

/**
 * How a display drives its playback timeline. This protocol-v2 enum travels on the wire as its
 * [ordinal] int ([wire] / [fromWire]); ordinals are append-only - never reorder or remove an entry.
 *
 * @since 1.8.x
 */
@Unstable
@Serializable
enum class PlaybackMode {
    /** No synchronization: every client plays independently of its own saved position. */
    LOCAL,

    /** Server-authoritative shared timeline; nearby players join the live position. */
    SYNCED,

    /** Ephemeral host-driven co-watch session layered over a base mode; never stored as a base mode. */
    WATCH_PARTY,

    /** Persistent server-driven public screen: loops forever, capped quality, always locked. */
    BROADCAST,

    ;

    /** The append-only wire value for this mode. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The mode for [wire], or [LOCAL] for unknown values (forward-compat with newer peers). */
        fun fromWire(wire: Int): PlaybackMode = byWire[wire] ?: LOCAL

        /** Persistable base modes a display can hold; excludes the ephemeral [WATCH_PARTY]. */
        val baseModes: List<PlaybackMode> = listOf(LOCAL, SYNCED, BROADCAST)

        /** True if [mode] is a valid persistent base mode (i.e. not [WATCH_PARTY]). */
        fun isBaseMode(mode: PlaybackMode): Boolean = mode != WATCH_PARTY
    }
}

/**
 * Who may change a display's playback and settings. Travels on the wire as its [ordinal] int
 * ([wire] / [fromWire]); ordinals are append-only.
 *
 * The display's owner and server admins always keep access, at every level. [REGION] is resolved
 * live against the `WorldGuard` region the display stands in, so it follows the region's member
 * list as it changes (and grants nobody extra where there is no region).
 *
 * @since 1.10.x
 */
@Unstable
@Serializable
enum class DisplayAccess {
    /** Any player may change the display, whether or not they belong to the region it stands in. */
    EVERYONE,

    /** Only members (and owners) of the `WorldGuard` region the display stands in. */
    REGION,

    /** Only the display's owner and server admins. */
    LOCKED,

    ;

    /** The append-only wire value for this level. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The level a display starts at when it is not created inside a region. */
        val DEFAULT: DisplayAccess = LOCKED

        /** The level for [wire], or [DEFAULT] for unknown values (forward-compat with newer peers). */
        fun fromWire(wire: Int): DisplayAccess = byWire[wire] ?: DEFAULT

        /** Maps the frozen-v1 boolean (which only knew locked / unlocked) onto a level. */
        fun fromLegacyLocked(locked: Boolean): DisplayAccess = if (locked) LOCKED else EVERYONE
    }
}

/** Lifecycle state of a watch-party session (see `WatchPartyStart` / `WatchPartyState`). */
@Unstable
enum class WatchPartySessionState {
    /** Host started the party and the display is session-locked; URL is being applied. */
    CREATED,

    /** Media is resolving and buffering on the host and joining clients. */
    PREPARING,

    /** Ready-check: host watches how many nearby players have marked themselves ready. */
    WAITING,

    /** Synchronized 3-2-1 countdown toward a shared start instant. */
    COUNTDOWN,

    /** Timeline is running, host-controlled. */
    PLAYING,

    /** Host paused the timeline. */
    PAUSED,

    /** Video finished or host ended it; the display freezes here until host / owner closes or restarts. */
    ENDED,

    ;

    /** The append-only wire value for this state. */
    val wire: Int get() = ordinal

    /** True once the session reached its frozen terminal state. */
    val isEnded: Boolean get() = this == ENDED

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The state for [wire], or [ENDED] for unknown values (newer peer -> treat as terminal). */
        fun fromWire(wire: Int): WatchPartySessionState = byWire[wire] ?: ENDED
    }
}

/** A playback intent a client sends to a server-authoritative timeline (`PlaybackCommand`). */
@Unstable
enum class PlaybackAction {
    PLAY, PAUSE, SEEK, RESTART;

    /** The append-only wire value for this action. */
    val wire: Int get() = ordinal

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The action for [wire], or null for unknown values (ignore unknown intents). */
        fun fromWire(wire: Int): PlaybackAction? = byWire[wire]
    }
}

/** A watch-party control sent by a participant ([READY]/[UNREADY]) or the host (everything else). */
@Unstable
enum class WatchPartyAction {
    READY, UNREADY, BEGIN, PAUSE, RESUME, SEEK, END, RESTART, CLOSE;

    /** The append-only wire value for this action. */
    val wire: Int get() = ordinal

    /** True if any nearby participant (not just the host) may send this action. */
    val isParticipantAction: Boolean get() = this == READY || this == UNREADY

    companion object {
        private val byWire = entries.associateBy { it.ordinal }

        /** The action for [wire], or null for unknown values (ignore unknown controls). */
        fun fromWire(wire: Int): WatchPartyAction? = byWire[wire]
    }
}
