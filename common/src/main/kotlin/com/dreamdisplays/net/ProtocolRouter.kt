package com.dreamdisplays.net

import com.dreamdisplays.managers.ClientPacketManager
import com.dreamdisplays.protocol.DisplaySync
import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.protocol.PacketRegistry
import com.dreamdisplays.protocol.ServerHello
import org.slf4j.LoggerFactory

/**
 * Client-side protocol negotiation: speaks v2 only after the server has proven v2 support by
 * answering the blind [com.dreamdisplays.protocol.ClientHello] with a [ServerHello].
 */
object ProtocolRouter {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ProtocolRouter")

    @Volatile
    var v2Negotiated: Boolean = false
        private set

    /**
     * Sends [packet] over v2 when negotiated, otherwise as the equivalent frozen-v1 payload.
     * v2-only packets (playback modes / watch parties) have no legacy form and are dropped on v1.
     */
    fun send(packet: DreamPacket) {
        if (v2Negotiated) {
            sendV2(packet)
        } else {
            val legacy = LegacyAdapter.toLegacy(packet)
            if (legacy != null) ClientPacketManager.send(legacy)
            else logger.debug("Dropping v2-only packet {} on a v1 server.", packet::class.simpleName)
        }
    }

    /** Sends [packet] over the v2 channel unconditionally; used for the blind hello bootstrap. */
    fun sendV2(packet: DreamPacket) {
        ClientPacketManager.send(V2Payload(PacketRegistry.encode(packet)))
    }

    /** Decodes and dispatches v2 envelope bytes; the first [ServerHello] flips the v2 switch. */
    fun onV2Received(bytes: ByteArray) {
        val packet = runCatching { PacketRegistry.decode(bytes) }
            .onFailure { logger.warn("Failed to decode v2 packet", it) }
            .getOrNull() ?: return
        if (packet is ServerHello && !v2Negotiated) {
            v2Negotiated = true
            logger.info("Protocol v2 negotiated (server protocol ${packet.protocolVersion}).")
        }
        ClientPacketManager.handle(packet)
    }

    /** Dispatches a packet adapted from a frozen-v1 payload; never flips the v2 switch. */
    @Deprecated("Protocol v1 dispatch path; remove when v1 client support is dropped.")
    fun onLegacyReceived(packet: DreamPacket) {
        if (v2Negotiated && packet is DisplaySync) {
            logger.debug("Ignoring legacy sync packet after protocol v2 negotiation.")
            return
        }
        ClientPacketManager.handle(packet)
    }

    /** Drops back to v1-until-proven on disconnect. */
    fun reset() {
        v2Negotiated = false
    }
}
