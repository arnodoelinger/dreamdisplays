package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.net.Packets
import com.dreamdisplays.core.protocol.ClearCache
import com.dreamdisplays.core.protocol.DisplayDelete
import com.dreamdisplays.core.protocol.DisplayInfo
import com.dreamdisplays.core.protocol.SetDisplaysEnabled
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.sync.SyncData
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.util.FacingUtil
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3i
import java.util.*

/**
 * Dual-protocol send facade for the vanilla Minecraft API flavor (`Fabric` / `NeoForge`):
 * v2-negotiated players receive envelope payloads, everyone else gets the frozen v1 payloads.
 * The only genuinely loader-specific bit — which networking API actually puts bytes on the wire —
 * is delegated to [VanillaNetworking.adapter].
 */
object VanillaPacketUtil {
    /** Splits the recipients into (v2-negotiated, legacy) lists. */
    private fun partition(players: List<ServerPlayer>): Pair<List<ServerPlayer>, List<ServerPlayer>> =
        players.partition { V2PlayerTracker.isV2(it.uuid) }

    /** Encodes and broadcasts a `display_info` packet describing a single display to [players]. */
    fun sendDisplayInfo(players: List<ServerPlayer>, display: VanillaDisplayData, forced: Boolean = false) {
        val isVertical = display.facing == Direction.UP || display.facing == Direction.DOWN
        val recipients = if (isVertical) players.filter { supportsVertical(it.uuid) } else players
        val (v2, legacy) = partition(recipients)
        VanillaNetworking.adapter.sendV2(
            v2,
            DisplayInfo(
                id = display.id, ownerId = display.ownerId,
                x = display.minX, y = display.minY, z = display.minZ,
                width = display.width, height = display.height, url = display.url,
                facing = directionToFacingUtil(display.facing).toPacket().toInt(),
                isSync = display.isSync, lang = display.lang, isLocked = display.isLocked,
                mode = display.mode.wire, qualityCap = display.qualityCap,
                rotation = display.rotation.quarterTurns,
                virtual = display.virtual, forced = forced,
            ),
        )
        if (legacy.isEmpty()) return
        val facing = directionToFacingUtil(display.facing)
        val packet = Packets.Info(
            uuid = display.id,
            ownerUuid = display.ownerId,
            pos = Vector3i(display.minX, display.minY, display.minZ),
            width = display.width,
            height = display.height,
            url = display.url,
            facingUtil = facing,
            isSync = display.isSync,
            lang = display.lang,
            isLocked = display.isLocked,
        )
        legacy.forEach { player -> VanillaNetworking.adapter.sendLegacy(player, packet) }
    }

    /**
     * Encodes and broadcasts a frozen-v1 `sync` packet. v2 timelines are server-authoritative
     * (see [TimelineManager]), so this path serves v1 peers only.
     */
    fun sendSync(players: List<ServerPlayer>, syncData: SyncData) {
        val id = syncData.id ?: return
        val (_, legacy) = partition(players)
        if (legacy.isEmpty()) return
        val packet = Packets.Sync(
            uuid = id,
            isSync = syncData.isSync,
            currentState = syncData.currentState,
            currentTime = syncData.currentTime,
            limitTime = syncData.limitTime
        )
        legacy.forEach { player -> VanillaNetworking.adapter.sendLegacy(player, packet) }
    }

    /** Tells [players] to remove the display with [id] from their local registry. */
    fun sendDelete(players: List<ServerPlayer>, id: UUID) {
        val (v2, legacy) = partition(players)
        VanillaNetworking.adapter.sendV2(v2, DisplayDelete(id))
        val packet = Packets.Delete(id)
        legacy.forEach { player -> VanillaNetworking.adapter.sendLegacy(player, packet) }
    }

    /** Notifies [player] whether they currently have premium permissions. */
    @Deprecated("Protocol v1 only; v2 bundles these flags in ServerHello. Remove when v1 support is dropped.")
    fun sendPremium(player: ServerPlayer, isPremium: Boolean) {
        VanillaNetworking.adapter.sendLegacy(player, Packets.Premium(isPremium))
    }

    /** Notifies [player] whether they are recognized as an admin (for delete privileges). */
    @Deprecated("Protocol v1 only; v2 bundles these flags in ServerHello. Remove when v1 support is dropped.")
    fun sendIsAdmin(player: ServerPlayer, isAdmin: Boolean) {
        VanillaNetworking.adapter.sendLegacy(player, Packets.IsAdmin(isAdmin))
    }

    /** Pushes the global displays-enabled flag for [player] to the client. */
    fun sendDisplayEnabled(player: ServerPlayer, isEnabled: Boolean) {
        if (V2PlayerTracker.isV2(player.uuid)) {
            VanillaNetworking.adapter.sendV2(listOf(player), SetDisplaysEnabled(isEnabled))
        } else {
            VanillaNetworking.adapter.sendLegacy(player, Packets.DisplayEnabled(isEnabled))
        }
    }

    /** Tells the client whether the report feature is enabled (i.e., a webhook is configured). */
    @Deprecated("Protocol v1 only; v2 bundles these flags in ServerHello. Remove when v1 support is dropped.")
    fun sendReportEnabled(player: ServerPlayer, isEnabled: Boolean) {
        VanillaNetworking.adapter.sendLegacy(player, Packets.ReportEnabled(isEnabled))
    }

    /** Tells [players] to evict the listed display UUIDs from any local caches. */
    fun sendClearCache(players: List<ServerPlayer>, uuids: List<UUID>) {
        if (uuids.isEmpty()) return
        val (v2, legacy) = partition(players)
        VanillaNetworking.adapter.sendV2(v2, ClearCache(uuids))
        if (legacy.isEmpty()) return
        val packet = Packets.ClearCache(uuids)
        legacy.forEach { player -> VanillaNetworking.adapter.sendLegacy(player, packet) }
    }

    /** Maps a [Direction] to its wire [FacingUtil]; faces not in the protocol fall back to north. */
    private fun directionToFacingUtil(direction: Direction): FacingUtil {
        return when (direction) {
            Direction.NORTH -> FacingUtil.NORTH
            Direction.EAST -> FacingUtil.EAST
            Direction.SOUTH -> FacingUtil.SOUTH
            Direction.WEST -> FacingUtil.WEST
            Direction.UP -> FacingUtil.UP
            Direction.DOWN -> FacingUtil.DOWN
        }
    }
}
