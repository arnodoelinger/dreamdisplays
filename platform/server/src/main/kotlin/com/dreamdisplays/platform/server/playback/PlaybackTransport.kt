package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.server.datatypes.DisplayData
import java.util.UUID

/**
 * The minimal platform surface the v2 playback backend ([TimelineManager], [WatchPartyManager])
 * needs. Implemented once per platform (`Paper` / `Fabric`) and injected at startup so the managers
 * themselves stay platform-agnostic. Everything here is v2-only — modes and watch parties never
 * degrade to frozen-v1, so there is no legacy path to mirror.
 */
interface PlaybackTransport {
    /** Server wall-clock in ms; the single time source for every authoritative timeline. */
    fun nowMs(): Long

    /** Broadcasts [packet] to every v2-negotiated player currently in range of [display]. */
    fun broadcast(display: DisplayData, packet: DreamPacket)

    /** Sends [packet] to one player by [playerId], if online and v2-negotiated. */
    fun sendTo(playerId: UUID, packet: DreamPacket)

    /** UUIDs of players currently in range of [display] (watch-party nearby / ready-check denominator). */
    fun nearbyPlayerIds(display: DisplayData): List<UUID>

    /** Display name for [playerId], or null if unknown / offline. */
    fun playerName(playerId: UUID): String?

    /** True if [playerId] is recognised as an admin (op / delete permission). */
    fun isAdmin(playerId: UUID): Boolean
}
