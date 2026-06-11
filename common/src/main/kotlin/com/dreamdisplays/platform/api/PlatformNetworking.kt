package com.dreamdisplays.platform.api

import com.dreamdisplays.protocol.DreamPacket

interface PlatformNetworking {
    fun sendToServer(packet: DreamPacket)
    fun sendToPlayer(playerId: String, packet: DreamPacket)
    fun sendToAll(packet: DreamPacket)
    fun onPacketReceived(handler: (DreamPacket) -> Unit): AutoCloseable
}
