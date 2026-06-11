package com.dreamdisplays.protocol

import com.dreamdisplays.api.DisplayFacing
import com.dreamdisplays.api.DisplayId

sealed interface DisplayPacket : DreamPacket

data class DisplayInfoPacket(
    val displayId: DisplayId,
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Int,
    val height: Int,
    val facing: DisplayFacing,
    val url: String?,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DisplayPacket

data class DisplayDeletePacket(
    val displayId: DisplayId,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DisplayPacket

data class DisplaySyncPacket(
    val displayId: DisplayId,
    val url: String,
    val positionMs: Long,
    val isPlaying: Boolean,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DisplayPacket

data class DisplayEnabledPacket(
    val enabled: Boolean,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DisplayPacket

data class ClearCachePacket(
    val displayId: DisplayId?,
    override val direction: PacketDirection = PacketDirection.SERVER_TO_CLIENT,
) : DreamPacket
