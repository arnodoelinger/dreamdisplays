@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/** Client playback intent for server-authoritative timeline. */
@Serializable
data class PlaybackCommand(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
) : DreamPacket

/** Client sets a display's persistent base mode. */
@Serializable
data class SetMode(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val mode: Int = 0,
    @ProtoNumber(3) val positionMs: Long = -1,
) : DreamPacket

/** Client pins / unpins display to PiP overlay; server persists per player. */
@Serializable
data class PipPin(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val pinned: Boolean = true,
) : DreamPacket

/** Client reports media duration after player initializes. */
@Serializable
data class ReportDuration(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val durationMs: Long = 0,
) : DreamPacket

/** Server tells client to pause / resume local-mode display player (no seek). */
@Serializable
data class RemotePlaybackToggle(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val paused: Boolean = true,
) : DreamPacket
