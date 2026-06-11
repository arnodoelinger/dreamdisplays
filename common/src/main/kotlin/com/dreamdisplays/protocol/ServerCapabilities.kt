package com.dreamdisplays.protocol

data class ServerCapabilities(
    val protocolVersion: Int = ProtocolVersion.CURRENT,
    val isPremium: Boolean = false,
    val isReportingEnabled: Boolean = false,
    val maxDisplays: Int = -1,
    val allowedFeatures: Set<String> = emptySet(),
)
