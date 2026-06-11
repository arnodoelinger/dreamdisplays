package com.dreamdisplays.protocol

data class ClientCapabilitiesPacket(
    val capabilities: ClientCapabilities,
    override val direction: PacketDirection = PacketDirection.CLIENT_TO_SERVER,
) : DreamPacket

data class ServerCapabilitiesPacket(
    val capabilities: ServerCapabilities,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DreamPacket
