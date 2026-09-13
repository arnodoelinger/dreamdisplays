@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Backend -> proxy: announces backend on first player join. */
@Serializable
data class BackendHello(
    @ProtoNumber(1) val pluginVersion: String = "",
    @ProtoNumber(2) val mcVersion: String = "",
    @ProtoNumber(3) val platform: String = "",
) : ProxyPacket

/** Proxy -> backend: answers [BackendHello] with server name and backend roster. */
@Serializable
data class ProxyWelcome(
    @ProtoNumber(1) val yourServerName: String = "",
    @ProtoNumber(2) val allServerNames: List<String> = emptyList(),
    @ProtoNumber(3) val proxyNowMs: Long = 0L,
) : ProxyPacket

/** Backend -> proxy: NTP-style clock probe for offset / latency estimation. */
@Serializable
data class ClockProbe(
    @ProtoNumber(1) val backendSendMs: Long = 0L,
) : ProxyPacket

/** Proxy -> backend: answers [ClockProbe] with offset estimation points. */
@Serializable
data class ClockReply(
    @ProtoNumber(1) val backendSendMs: Long = 0L,
    @ProtoNumber(2) val proxyRecvMs: Long = 0L,
    @ProtoNumber(3) val proxySendMs: Long = 0L,
) : ProxyPacket
