package com.dreamdisplays.protocol

sealed interface DreamPacket {
    val direction: PacketDirection
}
