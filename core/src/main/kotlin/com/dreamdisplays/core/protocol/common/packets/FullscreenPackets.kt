@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/** Server snapshot of fullscreen broadcast targeting receiving player. */
@Serializable
data class FullscreenState(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val displayId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) val active: Boolean = false,
    @ProtoNumber(4) val mode: Int = 0,
    @ProtoNumber(5) val forced: Boolean = false,
    @ProtoNumber(6) val volume: Float = -1f,
    @ProtoNumber(7) val title: String = "",
    @ProtoNumber(8) val loop: Boolean = false,
    @ProtoNumber(9) val quality: String = "",
    @ProtoNumber(10) val minimized: Boolean = false,
) : DreamPacket

/** Client ack for [FullscreenState]: shown (0), dismissed (1), minimized (2). */
@Serializable
data class FullscreenAck(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val action: Int = 0,
) : DreamPacket
