@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Backend -> proxy: requests the current network-wide fullscreen roster, for `/display fullscreen list`. */
@Serializable
data class ListNetworkSessions(
    @ProtoNumber(1) val unused: Int = 0,
) : ProxyPacket

/** One network session's aggregate state, as reported in a [NetworkSessionList]. */
@Serializable
data class NetworkSessionInfo(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val scope: String = "",
    @ProtoNumber(3) val url: String = "",
    @ProtoNumber(4) val totalReach: Int = 0,
)

/** Proxy -> backend: answers [ListNetworkSessions] with every live network fullscreen session. */
@Serializable
data class NetworkSessionList(
    @ProtoNumber(1) val sessions: List<NetworkSessionInfo> = emptyList(),
) : ProxyPacket

/** Backend -> proxy: player finished v2 handshake, sent on every join / server switch. */
@Serializable
data class PlayerReady(
    @ProtoNumber(1) val playerId: String = "",
) : ProxyPacket

/** Proxy -> backend: replay network fullscreen sessions for [playerId]. */
@Serializable
data class ReplayForPlayer(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val sessionIds: List<String> = emptyList(),
    @ProtoNumber(3) val minimizedSessionIds: List<String> = emptyList(),
) : ProxyPacket

/** Proxy -> backend: player transferring from [from] to [to]. */
@Serializable
data class PlayerTransferring(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val from: String = "",
    @ProtoNumber(3) val to: String = "",
) : ProxyPacket

/** Proxy -> backend: [playerId] left the network (not a switch). */
@Serializable
data class PlayerLeftNetwork(
    @ProtoNumber(1) val playerId: String = "",
    @ProtoNumber(2) val server: String = "",
) : ProxyPacket
