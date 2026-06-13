@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType

/*
 * Protocol-v2 wire packets. These classes are the schema: the committed .proto artifact is
 * generated from them (`./gradlew :protocol:generateProto`, drift-checked by SchemaDriftTest).
 *
 * Compatibility rules: breaking any of these breaks old peers:
 *  - Never reuse or renumber a @ProtoNumber; only add new fields with fresh numbers.
 *  - Every field keeps a default value so missing/extra fields never fail decoding.
 *  - Type ids (see PacketRegistry) are append-only.
 */

/** Client -> server hello; replaces the legacy `version` packet and advertises capabilities. */
@Serializable data class ClientHello(
    @ProtoNumber(1) val protocolVersion: Int = ProtocolVersion.CURRENT,
    @ProtoNumber(2) val modVersion: String = "",
    @ProtoNumber(3) val supportsPopout: Boolean = false,
    @ProtoNumber(4) val supportsHardwareDecode: Boolean = false,
    @ProtoNumber(5) val supportsHighResolution: Boolean = false,
    @ProtoNumber(6) val maxTextureSize: Int = 4096,
    @ProtoNumber(7) val supportedCodecs: List<String> = emptyList(),
    @ProtoNumber(8) val supportsPip: Boolean = false,
    @ProtoNumber(9) val supportsAudio: Boolean = true,
    @ProtoNumber(10) val renderBackend: String = "",
    @ProtoNumber(11) val shaderBackend: String = "",
    @ProtoNumber(12) val textureUploadPath: String = "",
    @ProtoNumber(13) val hwAccelBackend: String = "",
    @ProtoNumber(14) val nativeBackendAvailable: Boolean = false,
    @ProtoNumber(15) val nativeRgbaFramesEnabled: Boolean = false,
    @ProtoNumber(16) val nativeYuvGpuEnabled: Boolean = false,
    @ProtoNumber(17) val lavAvailable: Boolean = false,
    @ProtoNumber(18) val lavInProcessEnabled: Boolean = false,
    @ProtoNumber(19) val lavSurfaceInteropAvailable: Boolean = false,
    @ProtoNumber(20) val lavZeroCopyEnabled: Boolean = false,
) : DreamPacket

/**
 * Server -> client capability snapshot; folds the legacy `premium`, `is_admin` and
 * `report_enabled` flags. Re-sent in full whenever any flag changes — the client replaces its
 * snapshot wholesale. Field number 5 is retired (was `displaysEnabled`, now [SetDisplaysEnabled]).
 */
@Serializable data class ServerHello(
    @ProtoNumber(1) val protocolVersion: Int = ProtocolVersion.CURRENT,
    @ProtoNumber(2) val isPremium: Boolean = false,
    @ProtoNumber(3) val isAdmin: Boolean = false,
    @ProtoNumber(4) val isReportingEnabled: Boolean = false,
    @ProtoNumber(6) val maxDisplays: Int = -1,
    @ProtoNumber(7) val allowedFeatures: List<String> = emptyList(),
) : DreamPacket

/** Full description of a single display; `facing` uses the legacy byte mapping 0=N 1=E 2=S 3=W. */
@Serializable data class DisplayInfo(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
    @ProtoNumber(2) val ownerId: ProtoUuid = ZERO_UUID,
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
) : DreamPacket

/** Removes a display (server broadcast) or requests its deletion (client action). */
@Serializable data class DisplayDelete(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
) : DreamPacket

/** Playback state for a synchronized display; travels in both directions. */
@Serializable data class DisplaySync(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
    @ProtoNumber(2) val isSync: Boolean = false,
    @ProtoNumber(3) val isPaused: Boolean = false,
    @ProtoNumber(4) val currentTimeMs: Long = 0,
    @ProtoNumber(5) val durationMs: Long = 0,
) : DreamPacket

/** Client asks the server for the authoritative playback state of a display. */
@Serializable data class RequestSync(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
) : DreamPacket

/** Client applies a new media URL / audio language to a display. */
@Serializable data class SetVideo(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : DreamPacket

/** Client toggles the locked flag of a display it owns. */
@Serializable data class SetLocked(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
    @ProtoNumber(2) val locked: Boolean = true,
) : DreamPacket

/** Client reports a display to the server's configured webhook. */
@Serializable data class ReportDisplay(
    @ProtoNumber(1) val id: ProtoUuid = ZERO_UUID,
) : DreamPacket

/**
 * Display rendering toggle: client -> server persists the player's preference; server -> client
 * forces the toggle (admin `/display on|off` commands), mirroring the legacy `display_enabled` channel.
 */
@Serializable data class SetDisplaysEnabled(
    @ProtoNumber(1) val enabled: Boolean = true,
) : DreamPacket

/** Server tells the client to evict the listed displays from local caches. */
@Serializable data class ClearCache(
    @ProtoNumber(1) val ids: List<ProtoUuid> = emptyList(),
) : DreamPacket
