package com.dreamdisplays.protocol

data class ClientCapabilities(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val supportsPopout: Boolean = false,
    val supportsHardwareDecode: Boolean = false,
    val supportsHighResolution: Boolean = false,
    val maxTextureSize: Int = 4096,
    val supportedCodecs: List<String> = emptyList(),
    val supportsPip: Boolean = false,
    val supportsAudio: Boolean = true,
)
