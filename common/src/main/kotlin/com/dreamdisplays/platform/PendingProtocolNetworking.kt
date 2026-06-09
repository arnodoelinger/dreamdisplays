package com.dreamdisplays.platform

import com.dreamdisplays.platform.api.PlatformNetworking
import com.dreamdisplays.protocol.DreamPacket

/**
 * Placeholder [PlatformNetworking] for the protocol-v2 rewrite. [DreamPacket]s have no wire codecs
 * yet.
 *
 * The live protocol still runs on the legacy [com.dreamdisplays.net.Packets] payloads sent
 * via [com.dreamdisplays.Initializer.sendPacket], so every transport call here fails loudly
 * rather than silently dropping packets. Replace once the v2 codecs land.
 *
 * Maybe protocol-v2 will rely on proto.
 */
object PendingProtocolNetworking : PlatformNetworking {

    override fun sendToServer(packet: DreamPacket): Nothing = unsupported()
    override fun sendToPlayer(playerId: String, packet: DreamPacket): Nothing = unsupported()
    override fun sendToAll(packet: DreamPacket): Nothing = unsupported()
    override fun onPacketReceived(handler: (DreamPacket) -> Unit): Nothing = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "DreamPacket wire codecs are not implemented yet; use the legacy Packets payloads via Initializer.sendPacket."
    )
}
