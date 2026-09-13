@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Backend -> proxy: forward `/display fullscreen start` command. */
@Serializable
data class StartNetworkFullscreen(
    @ProtoNumber(1) val scope: String = "",
    @ProtoNumber(2) val ownerId: String = "",
    @ProtoNumber(3) val url: String = "",
    @ProtoNumber(4) val mode: Int = 0,
    @ProtoNumber(5) val forced: Boolean = false,
    @ProtoNumber(6) val volume: Float = -1f,
    @ProtoNumber(7) val loop: Boolean = false,
    @ProtoNumber(8) val quality: String = "",
    @ProtoNumber(9) val title: String = "",
    @ProtoNumber(10) val targetsRaw: String = "",
) : ProxyPacket

/** Proxy -> backend: fan-out of [StartNetworkFullscreen] with sessionId for sync. */
@Serializable
data class ApplyFullscreen(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val anchorProxyMs: Long = 0L,
    @ProtoNumber(11) val sharedDisplayId: String = "",
    @ProtoNumber(3) val ownerId: String = "",
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val mode: Int = 0,
    @ProtoNumber(6) val forced: Boolean = false,
    @ProtoNumber(7) val volume: Float = -1f,
    @ProtoNumber(8) val loop: Boolean = false,
    @ProtoNumber(9) val quality: String = "",
    @ProtoNumber(10) val title: String = "",
    @ProtoNumber(12) val targetsRaw: String = "",
) : ProxyPacket

/** Bidirectional: stop network fullscreen everywhere. */
@Serializable
data class StopNetworkFullscreen(
    @ProtoNumber(1) val sessionId: String = "",
) : ProxyPacket

/** Backend -> proxy: report outcome of [ApplyFullscreen]. */
@Serializable
data class NetworkFullscreenAck(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val reach: Int = 0,
    @ProtoNumber(3) val pending: Boolean = false,
) : ProxyPacket

/** Backend -> proxy: viewer collapsed / restored network fullscreen to / from PiP. */
@Serializable
data class PlayerFullscreenMinimized(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val playerId: String = "",
    @ProtoNumber(3) val minimized: Boolean = false,
) : ProxyPacket
