@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.core.protocol.common.UuidSerializer
import com.dreamdisplays.core.protocol.common.ZERO_UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import java.util.*

/** Full description of a single display. */
@Serializable
data class DisplayInfo(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val ownerId: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.SIGNED) val x: Int = 0,
    @ProtoNumber(4) @ProtoType(ProtoIntegerType.SIGNED) val y: Int = 0,
    @ProtoNumber(5) @ProtoType(ProtoIntegerType.SIGNED) val z: Int = 0,
    @ProtoNumber(6) val width: Int = 1,
    @ProtoNumber(7) val height: Int = 1,
    @ProtoNumber(8) val url: String = "",
    @ProtoNumber(9) val facing: Int = 0,
    @ProtoNumber(10) val isSync: Boolean = false,
    @ProtoNumber(11) val lang: String = "",
    @ProtoNumber(12) val isLocked: Boolean = true,
    @ProtoNumber(13) val mode: Int = 0,
    @ProtoNumber(14) val qualityCap: Int = 0,
    @ProtoNumber(15) val rotation: Int = 0,
    @ProtoNumber(16) val virtual: Boolean = false,
    @ProtoNumber(17) val forced: Boolean = false,
    @ProtoNumber(18) val scheduledStartEpochMillis: Long = 0,
    @ProtoNumber(19) val scheduledAction: Int = -1,
    /** Wire ordinal of the display's `DisplayAccess`; [isLocked] stays as its frozen-v1 mirror. */
    @ProtoNumber(20) val access: Int = 2,
    /** True when the display stands inside a `WorldGuard` region, so the region access level means something here. */
    @ProtoNumber(21) val inRegion: Boolean = false,
    /**
     * Whether *the player this copy was addressed to* belongs to that region — the one thing a client
     * cannot work out for itself, since region membership only exists server-side. Meaningful only
     * while [access] is the region level; the other levels are the same for every viewer.
     */
    @ProtoNumber(22) val viewerInRegion: Boolean = false,
) : DreamPacket

/** Removes a display (server broadcast) or requests its deletion (client action). */
@Serializable
data class DisplayDelete(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Authoritative playback timeline for a display, pushed by server; [currentTimeMs] is position anchor. */
@Serializable
data class DisplaySync(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val isSync: Boolean = false,
    @ProtoNumber(3) val isPaused: Boolean = false,
    @ProtoNumber(4) val currentTimeMs: Long = 0,
    @ProtoNumber(5) val durationMs: Long = 0,
    @ProtoNumber(6) val serverTimeMs: Long = 0,
    @ProtoNumber(7) val loop: Boolean = false,
    @ProtoNumber(8) val mode: Int = 0,
) : DreamPacket

/** Client asks the server for the authoritative playback state of a display. */
@Serializable
data class RequestSync(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Client applies a new media URL / audio language to a display. */
@Serializable
data class SetVideo(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : DreamPacket

/**
 * Client changes who may use a display it owns. [locked] is what pre-1.10 clients send and all this
 * packet could once express; [access] carries the full level and wins whenever it is present.
 */
@Serializable
data class SetLocked(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val locked: Boolean = true,
    /** Wire ordinal of the requested `DisplayAccess`, or `-1` from a client that only knows [locked]. */
    @ProtoNumber(3) val access: Int = -1,
) : DreamPacket

/**
 * The access level [SetLocked] asks for: its own [SetLocked.access] when the sending client knows
 * about levels, otherwise the level its [SetLocked.locked] boolean maps onto.
 */
fun SetLocked.accessLevel(): DisplayAccess =
    if (access >= 0) DisplayAccess.fromWire(access) else DisplayAccess.fromLegacyLocked(locked)

/** Client reports a display to the server's configured webhook. */
@Serializable
data class ReportDisplay(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/** Display rendering toggle: bidirectional preference / admin control. */
@Serializable
data class SetDisplaysEnabled(
    @ProtoNumber(1) val enabled: Boolean = true,
) : DreamPacket

/** Server asks admin's client to render / hide fullscreen-broadcast radius preview. */
@Serializable
data class RadiusPreview(
    @ProtoNumber(1) val x: Double = 0.0,
    @ProtoNumber(2) val y: Double = 0.0,
    @ProtoNumber(3) val z: Double = 0.0,
    @ProtoNumber(4) val radius: Double = 0.0,
    @ProtoNumber(5) val show: Boolean = false,
    @ProtoNumber(6) val colorArgb: Int = 0,
) : DreamPacket
