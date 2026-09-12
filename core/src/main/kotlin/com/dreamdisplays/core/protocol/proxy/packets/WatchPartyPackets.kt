@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy.packets

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Backend -> proxy: host requests network-wide watch party. */
@Serializable
data class StartNetworkWatchParty(
    @ProtoNumber(1) val hostId: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val lang: String = "",
) : ProxyPacket

/** Proxy -> backend: answer [StartNetworkWatchParty] with [sharedDisplayId]. */
@Serializable
data class ApplyNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val hostId: String = "",
    @ProtoNumber(4) val url: String = "",
    @ProtoNumber(5) val lang: String = "",
) : ProxyPacket

/** Proxy -> backend: register [playerId] as member of party [partyId]. */
@Serializable
data class JoinNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val playerId: String = "",
    @ProtoNumber(4) val hostId: String = "",
    @ProtoNumber(5) val url: String = "",
    @ProtoNumber(6) val lang: String = "",
) : ProxyPacket

/** Bidirectional: network watch party state; mirrors [com.dreamdisplays.core.protocol.common.packets.WatchPartyState]. */
@Serializable
data class NetworkWatchPartyState(
    @ProtoNumber(1) val partyId: String = "",
    @ProtoNumber(2) val sharedDisplayId: String = "",
    @ProtoNumber(3) val state: Int = 0,
    @ProtoNumber(4) val hostId: String = "",
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
) : ProxyPacket

/**
 * Bidirectional: tears a network watch party down everywhere — a backend requests it (host closed
 * the party locally), or the proxy fans it back out to every backend with members, same as
 * [StopNetworkFullscreen][com.dreamdisplays.core.protocol.proxy.packets.StopNetworkFullscreen].
 */
@Serializable
data class CloseNetworkWatchParty(
    @ProtoNumber(1) val partyId: String = "",
) : ProxyPacket
