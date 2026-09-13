package com.dreamdisplays.platform.server.playback

import com.dreamdisplays.core.protocol.common.packets.DreamPacket
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import java.util.*

/** The minimal platform surface the v2 playback backend ([TimelineManager], [WatchPartyManager]) needs. */
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

    /**
     * True if [playerId] is a member (or owner) of the `WorldGuard` region [display] stands in.
     * Resolved live against the region, so it follows member-list edits; always false off-Paper,
     * without `WorldGuard`, or where the display stands in no region.
     */
    fun isTerritoryMember(display: DisplayData, playerId: UUID): Boolean

    /** UUIDs of every online player; fullscreen radius targeting scans the whole player list, not just those near a display. */
    fun onlinePlayerIds(): List<UUID>

    /** Squared distance from [playerId] to (`x`, `y`, `z`) in [world], or null when offline or in a different world. */
    fun playerDistanceSq(playerId: UUID, world: String, x: Double, y: Double, z: Double): Double?

    /** Sends [display]'s `DisplayInfo` to one [playerId], regardless of render distance. */
    fun sendDisplayInfo(playerId: UUID, display: DisplayData, forced: Boolean)

    /** Builds a synthetic 1 x 1 [DisplayData] anchored in a loaded world, backing a URL-only fullscreen broadcast; null if no world is loaded. */
    fun createVirtualDisplay(id: UUID, ownerId: UUID): DisplayData?

    /** Runs [task] on the main / global region thread, e.g. before touching `Bukkit` / NMS state from a background coroutine. */
    fun runOnMainThread(task: () -> Unit)

    /**
     * Persists [display] to the platform's [com.dreamdisplays.platform.server.managers.StorageManager],
     * dispatching to the platform-typed `saveDisplay` overload.
     */
    fun saveDisplay(display: DisplayData)
}
