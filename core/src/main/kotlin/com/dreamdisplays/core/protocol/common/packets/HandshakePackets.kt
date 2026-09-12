@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.common.packets

import com.dreamdisplays.api.protocol.ProtocolVersion
import com.dreamdisplays.core.protocol.common.UuidSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.util.*

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
    @ProtoNumber(27) val timeZoneOffsetMinutes: Int = 0,
) : DreamPacket

/** Server -> client capability snapshot (premium, admin, reporting); field 5 retired. */
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

/** Server tells the client to evict the listed displays from local caches. */
@Serializable
data class ClearCache(
    @ProtoNumber(1) val ids: List<@Serializable(UuidSerializer::class) UUID> = emptyList(),
) : DreamPacket
