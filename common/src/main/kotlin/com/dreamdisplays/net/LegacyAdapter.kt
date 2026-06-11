package com.dreamdisplays.net

import com.dreamdisplays.managers.ClientPacketManager
import com.dreamdisplays.protocol.ClearCache
import com.dreamdisplays.protocol.ClientHello
import com.dreamdisplays.protocol.DisplayDelete
import com.dreamdisplays.protocol.DisplayInfo
import com.dreamdisplays.protocol.DisplaySync
import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.protocol.ReportDisplay
import com.dreamdisplays.protocol.RequestSync
import com.dreamdisplays.protocol.SetDisplaysEnabled
import com.dreamdisplays.protocol.SetLocked
import com.dreamdisplays.protocol.SetVideo
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * The only place where frozen-v1 payloads and protocol-v2 packets meet on the mod side.
 * Outgoing v2 packets degrade to their v1 equivalent for old servers; incoming v1 payloads are
 * lifted into v2 types so the business logic exists once.
 */
object LegacyAdapter {

    /** Maps an outgoing v2 [packet] to its frozen-v1 payload for servers without v2. */
    fun toLegacy(packet: DreamPacket): CustomPacketPayload = when (packet) {
        is ClientHello -> Packets.Version(packet.modVersion)
        is DisplaySync -> Packets.Sync(packet.id, packet.isSync, packet.isPaused, packet.currentTimeMs, packet.durationMs)
        is RequestSync -> Packets.RequestSync(packet.id)
        is SetVideo -> Packets.SetVideo(packet.id, packet.url, packet.lang)
        is SetLocked -> Packets.SetLocked(packet.id, packet.locked)
        is DisplayDelete -> Packets.Delete(packet.id)
        is ReportDisplay -> Packets.Report(packet.id)
        is SetDisplaysEnabled -> Packets.DisplayEnabled(packet.enabled)
        else -> error("No legacy equivalent for ${packet::class.simpleName}.")
    }

    /**
     * Lifts an incoming frozen-v1 [payload] into its v2 packet. Per-flag legacy packets merge
     * into the current [ServerHello][com.dreamdisplays.protocol.ServerHello] snapshot.
     */
    fun fromLegacy(payload: CustomPacketPayload): DreamPacket = when (payload) {
        is Packets.Info -> DisplayInfo(
            id = payload.uuid,
            ownerId = payload.ownerUuid,
            x = payload.pos.x, y = payload.pos.y, z = payload.pos.z,
            width = payload.width, height = payload.height,
            url = payload.url,
            facing = payload.facingUtil.toPacket().toInt(),
            isSync = payload.isSync,
            lang = payload.lang,
            isLocked = payload.isLocked ?: true,
        )
        is Packets.Sync -> DisplaySync(payload.uuid, payload.isSync, payload.currentState, payload.currentTime, payload.limitTime)
        is Packets.Delete -> DisplayDelete(payload.uuid)
        is Packets.ClearCache -> ClearCache(payload.displayUuids)
        is Packets.Premium -> ClientPacketManager.serverSnapshot.copy(isPremium = payload.premium)
        is Packets.IsAdmin -> ClientPacketManager.serverSnapshot.copy(isAdmin = payload.isAdmin)
        is Packets.ReportEnabled -> ClientPacketManager.serverSnapshot.copy(isReportingEnabled = payload.enabled)
        is Packets.DisplayEnabled -> SetDisplaysEnabled(payload.enabled)
        else -> error("No v2 equivalent for ${payload::class.simpleName}.")
    }
}
