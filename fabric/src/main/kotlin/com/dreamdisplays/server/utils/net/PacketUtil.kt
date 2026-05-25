package com.dreamdisplays.server.utils.net

import com.dreamdisplays.net.Packets
import com.dreamdisplays.server.datatypes.DisplayData
import com.dreamdisplays.server.datatypes.SyncData
import com.dreamdisplays.utils.FacingUtil
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import org.joml.Vector3i
import java.util.*

/**
 * Different from `Paper` implementation.
 *
 * Utility object for sending packets to players.
 *
 * Provides methods to send various types of packets to players,
 * including display info, sync data, delete commands, and settings updates.
 *
 * `Fabric server` implementation.
 */
object PacketUtil {
    // Fabric server start
    fun sendDisplayInfo(players: List<ServerPlayer>, display: DisplayData) {
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
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    fun sendSync(players: List<ServerPlayer>, syncData: SyncData) {
        val id = syncData.id ?: return
        val packet = Packets.Sync(
            uuid = id,
            isSync = syncData.isSync,
            currentState = syncData.currentState,
            currentTime = syncData.currentTime,
            limitTime = syncData.limitTime
        )
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    fun sendDelete(players: List<ServerPlayer>, id: UUID) {
        val packet = Packets.Delete(id)
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    fun sendPremium(player: ServerPlayer, isPremium: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.Premium(isPremium)) }
    }

    fun sendDisplayEnabled(player: ServerPlayer, isEnabled: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.DisplayEnabled(isEnabled)) }
    }

    fun sendReportEnabled(player: ServerPlayer, isEnabled: Boolean) {
        runCatching { ServerPlayNetworking.send(player, Packets.ReportEnabled(isEnabled)) }
    }

    fun sendClearCache(players: List<ServerPlayer>, uuids: List<UUID>) {
        if (uuids.isEmpty()) return
        val packet = Packets.ClearCache(uuids)
        players.forEach { player ->
            runCatching { ServerPlayNetworking.send(player, packet) }
        }
    }

    private fun directionToFacingUtil(direction: Direction): FacingUtil {
        return when (direction) {
            Direction.NORTH -> FacingUtil.NORTH
            Direction.EAST -> FacingUtil.EAST
            Direction.SOUTH -> FacingUtil.SOUTH
            Direction.WEST -> FacingUtil.WEST
            else -> FacingUtil.NORTH
        }
    }
    // Fabric server end
}
