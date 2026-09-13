@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

/** Client starts a watch-party session on a display, becoming its host (display must be unlocked, or owner). */
@Serializable
data class WatchPartyStart(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : DreamPacket

/** Watch-party participant readiness or host control. */
@Serializable
data class WatchPartyControl(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
) : DreamPacket

/** Server snapshot of watch-party session, broadcast on every transition. */
@Serializable
data class WatchPartyState(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val sessionId: String = "",
    @ProtoNumber(3) val state: Int = 0,
    @ProtoNumber(4) val hostId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(5) val hostName: String = "",
    @ProtoNumber(6) val url: String = "",
    @ProtoNumber(7) val lang: String = "",
    @ProtoNumber(8) val readyCount: Int = 0,
    @ProtoNumber(9) val nearbyCount: Int = 0,
    @ProtoNumber(10) val countdownStartEpochMs: Long = 0,
    @ProtoNumber(11) val positionMs: Long = 0,
    @ProtoNumber(12) val serverTimeMs: Long = 0,
    @ProtoNumber(13) val durationMs: Long = 0,
    @ProtoNumber(14) val paused: Boolean = true,
) : DreamPacket
