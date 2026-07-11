@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol

import com.dreamdisplays.api.protocol.ProtocolVersion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import java.util.UUID

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
@Serializable
data class ClientHello(
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
    @ProtoNumber(21) val systemRamMb: Int = 0,
    @ProtoNumber(22) val maxJvmMemoryMb: Int = 0,
    @ProtoNumber(23) val dedicatedVramMb: Int = 0,
    @ProtoNumber(24) val warmDisplayLimit: Int = 0,
    @ProtoNumber(25) val nativeUnavailableReason: String = "",
    @ProtoNumber(26) val lavUnavailableReason: String = "",
) : DreamPacket

/**
 * Server -> client capability snapshot; folds the legacy `premium`, `is_admin` and
 * `report_enabled` flags. Re-sent in full whenever any flag changes — the client replaces its
 * snapshot wholesale. Field number 5 is retired (was `displaysEnabled`, now [SetDisplaysEnabled]).
 */
@Serializable
data class ServerHello(
    @ProtoNumber(1) val protocolVersion: Int = ProtocolVersion.CURRENT,
    @ProtoNumber(2) val isPremium: Boolean = false,
    @ProtoNumber(3) val isAdmin: Boolean = false,
    @ProtoNumber(4) val isReportingEnabled: Boolean = false,
    @ProtoNumber(6) val maxDisplays: Int = -1,
    @ProtoNumber(7) val allowedFeatures: List<String> = emptyList(),
    @ProtoNumber(8) val defaultVolume: Float = -1f,
) : DreamPacket

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
) : DreamPacket

/** Removes a display (server broadcast) or requests its deletion (client action). */
@Serializable
data class DisplayDelete(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/**
 * Authoritative playback timeline for a display, pushed by the server. [currentTimeMs] is the
 * position as of [serverTimeMs]; clients extrapolate `position + (estServerNow - serverTimeMs)`
 * (wrapping by [durationMs] when [loop]) and only seek when their drift exceeds tolerance.
 *
 * Still travels in both directions for frozen-v1 compatibility, but v2 clients no longer report
 * their own clock here — they send intents via [PlaybackCommand] and the server owns the timeline.
 */
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

/** Client toggles the locked flag of a display it owns. */
@Serializable
data class SetLocked(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val locked: Boolean = true,
) : DreamPacket

/** Client reports a display to the server's configured webhook. */
@Serializable
data class ReportDisplay(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
) : DreamPacket

/**
 * Display rendering toggle: client -> server persists the player's preference; server -> client
 * forces the toggle (admin `/display on|off` commands), mirroring the legacy `display_enabled` channel.
 */
@Serializable
data class SetDisplaysEnabled(
    @ProtoNumber(1) val enabled: Boolean = true,
) : DreamPacket

/** Server tells the client to evict the listed displays from local caches. */
@Serializable
data class ClearCache(
    @ProtoNumber(1) val ids: List<@Serializable(UuidSerializer::class) UUID> = emptyList(),
) : DreamPacket

/**
 * Client playback intent for a server-authoritative timeline (a `SYNCED` display the client may
 * edit, or a watch-party host). The server validates permission, updates its clock, and rebroadcasts
 * the new [DisplaySync]. [action] is a [PlaybackAction.wire]; [positionMs] is used by `SEEK`.
 */
@Serializable
data class PlaybackCommand(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
) : DreamPacket

/** Client sets a display's persistent base mode; [mode] is a [PlaybackMode.wire] (not `WATCH_PARTY`). */
@Serializable
data class SetMode(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val mode: Int = 0,
    @ProtoNumber(3) val positionMs: Long = -1,
) : DreamPacket

/** Client starts a watch-party session on a display, becoming its host (display must be unlocked, or owner). */
@Serializable
data class WatchPartyStart(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : DreamPacket

/**
 * Participant readiness ([WatchPartyAction.READY]/[WatchPartyAction.UNREADY], any nearby player) or
 * host control (everything else) for an active session. [positionMs] is used by `SEEK`.
 */
@Serializable
data class WatchPartyControl(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val action: Int = 0,
    @ProtoNumber(3) val positionMs: Long = 0,
) : DreamPacket

/**
 * Server snapshot of a watch-party session, broadcast to nearby clients on every transition.
 * [state] is a [WatchPartySessionState.wire]; [positionMs] is the timeline position as of
 * [serverTimeMs]; [countdownStartEpochMs] is the shared instant `COUNTDOWN` resolves to `PLAYING`.
 */
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

/**
 * Server snapshot of a fullscreen broadcast targeting the receiving player. Idempotent: re-sent in
 * full on reconnect / target-set changes; [active] = false tears the overlay down. [displayId]
 * references a [DisplayInfo] the server has already delivered (a world display or a virtual one).
 * [mode] is a [FullscreenMode] wire ordinal (0 standard, 1 immersive); [volume] < 0 keeps the
 * client's own volume.
 */
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
) : DreamPacket

/**
 * Client acknowledges a [FullscreenState] transition: 0 = shown, 1 = dismissed (unforced close),
 * 2 = minimized to PiP. Lets the server report broadcast reach and stop re-sending to players who
 * dismissed an unforced broadcast.
 */
@Serializable
data class FullscreenAck(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val action: Int = 0,
) : DreamPacket

/**
 * Client pins (or unpins) a display to its Picture-in-Picture overlay. The server persists this per
 * player so a pinned display's [DisplayInfo] gets re-sent (bypassing render distance, like a forced
 * fullscreen broadcast) on the next [ClientHello] handshake, letting the client re-open the overlay
 * even if the display is far outside normal render distance - otherwise PiP is lost on every rejoin.
 */
@Serializable
data class PipPin(
    @ProtoNumber(1) val id: @Serializable(UuidSerializer::class) UUID = ZERO_UUID,
    @ProtoNumber(2) val pinned: Boolean = true,
) : DreamPacket

/**
 * Server asks the issuing admin's client to render (or hide) a translucent radius-preview dome at
 * a world position, visualizing a fullscreen-broadcast radius while it is being configured.
 */
@Serializable
data class RadiusPreview(
    @ProtoNumber(1) val x: Double = 0.0,
    @ProtoNumber(2) val y: Double = 0.0,
    @ProtoNumber(3) val z: Double = 0.0,
    @ProtoNumber(4) val radius: Double = 0.0,
    @ProtoNumber(5) val show: Boolean = false,
    @ProtoNumber(6) val colorArgb: Int = 0,
) : DreamPacket
