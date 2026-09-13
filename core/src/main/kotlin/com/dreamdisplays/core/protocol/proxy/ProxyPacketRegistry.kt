@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy

import com.dreamdisplays.core.protocol.proxy.packets.ApplyFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.ApplyNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.BackendDisplayIndex
import com.dreamdisplays.core.protocol.proxy.packets.BackendHello
import com.dreamdisplays.core.protocol.proxy.packets.ClockProbe
import com.dreamdisplays.core.protocol.proxy.packets.ClockReply
import com.dreamdisplays.core.protocol.proxy.packets.CloseNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.DisplayTokenResolved
import com.dreamdisplays.core.protocol.proxy.packets.ProxyPacket
import com.dreamdisplays.core.protocol.proxy.packets.JoinNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.ListNetworkSessions
import com.dreamdisplays.core.protocol.proxy.packets.NetworkFullscreenAck
import com.dreamdisplays.core.protocol.proxy.packets.NetworkSessionList
import com.dreamdisplays.core.protocol.proxy.packets.NetworkWatchPartyState
import com.dreamdisplays.core.protocol.proxy.packets.PlayerFullscreenMinimized
import com.dreamdisplays.core.protocol.proxy.packets.PlayerLeftNetwork
import com.dreamdisplays.core.protocol.proxy.packets.PlayerReady
import com.dreamdisplays.core.protocol.proxy.packets.PlayerTransferring
import com.dreamdisplays.core.protocol.proxy.packets.ProxyWelcome
import com.dreamdisplays.core.protocol.proxy.packets.ReplayForPlayer
import com.dreamdisplays.core.protocol.proxy.packets.ResolveDisplayToken
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkFullscreen
import com.dreamdisplays.core.protocol.proxy.packets.StartNetworkWatchParty
import com.dreamdisplays.core.protocol.proxy.packets.StopNetworkFullscreen
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.reflect.KClass
import kotlin.reflect.cast

/** Wire frame for the `dreamdisplays:proxy` channel: a type id plus the encoded packet bytes. */
@Serializable
data class ProxyEnvelope(
    @ProtoNumber(1) val type: Int = 0,
    @ProtoNumber(2) val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is ProxyEnvelope && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/** Proxy packet registry. */
object ProxyPacketRegistry {
    private val proto = ProtoBuf { encodeDefaults = false }

    private class Entry<T : ProxyPacket>(
        val packetType: ProxyPacketType,
        val type: KClass<T>,
        val serializer: KSerializer<T>,
    ) {
        val id: Int get() = packetType.id
        val direction: ProxyPacketDirection get() = packetType.direction

        fun encode(proto: ProtoBuf, packet: ProxyPacket): ByteArray = proto.encodeToByteArray(serializer, type.cast(packet))
    }

    private val entries: List<Entry<out ProxyPacket>> = listOf(
        Entry(ProxyPacketType.BACKEND_HELLO, BackendHello::class, BackendHello.serializer()),
        Entry(ProxyPacketType.PROXY_WELCOME, ProxyWelcome::class, ProxyWelcome.serializer()),
        Entry(ProxyPacketType.CLOCK_PROBE, ClockProbe::class, ClockProbe.serializer()),
        Entry(ProxyPacketType.CLOCK_REPLY, ClockReply::class, ClockReply.serializer()),
        Entry(ProxyPacketType.START_NETWORK_FULLSCREEN, StartNetworkFullscreen::class, StartNetworkFullscreen.serializer()),
        Entry(ProxyPacketType.APPLY_FULLSCREEN, ApplyFullscreen::class, ApplyFullscreen.serializer()),
        Entry(ProxyPacketType.STOP_NETWORK_FULLSCREEN, StopNetworkFullscreen::class, StopNetworkFullscreen.serializer()),
        Entry(ProxyPacketType.NETWORK_FULLSCREEN_ACK, NetworkFullscreenAck::class, NetworkFullscreenAck.serializer()),
        Entry(ProxyPacketType.LIST_NETWORK_SESSIONS, ListNetworkSessions::class, ListNetworkSessions.serializer()),
        Entry(ProxyPacketType.NETWORK_SESSION_LIST, NetworkSessionList::class, NetworkSessionList.serializer()),
        Entry(ProxyPacketType.PLAYER_READY, PlayerReady::class, PlayerReady.serializer()),
        Entry(ProxyPacketType.REPLAY_FOR_PLAYER, ReplayForPlayer::class, ReplayForPlayer.serializer()),
        Entry(ProxyPacketType.PLAYER_TRANSFERRING, PlayerTransferring::class, PlayerTransferring.serializer()),
        Entry(ProxyPacketType.PLAYER_LEFT_NETWORK, PlayerLeftNetwork::class, PlayerLeftNetwork.serializer()),
        Entry(ProxyPacketType.START_NETWORK_WATCH_PARTY, StartNetworkWatchParty::class, StartNetworkWatchParty.serializer()),
        Entry(ProxyPacketType.APPLY_NETWORK_WATCH_PARTY, ApplyNetworkWatchParty::class, ApplyNetworkWatchParty.serializer()),
        Entry(ProxyPacketType.JOIN_NETWORK_WATCH_PARTY, JoinNetworkWatchParty::class, JoinNetworkWatchParty.serializer()),
        Entry(ProxyPacketType.NETWORK_WATCH_PARTY_STATE, NetworkWatchPartyState::class, NetworkWatchPartyState.serializer()),
        Entry(ProxyPacketType.CLOSE_NETWORK_WATCH_PARTY, CloseNetworkWatchParty::class, CloseNetworkWatchParty.serializer()),
        Entry(ProxyPacketType.RESOLVE_DISPLAY_TOKEN, ResolveDisplayToken::class, ResolveDisplayToken.serializer()),
        Entry(ProxyPacketType.DISPLAY_TOKEN_RESOLVED, DisplayTokenResolved::class, DisplayTokenResolved.serializer()),
        Entry(ProxyPacketType.PLAYER_FULLSCREEN_MINIMIZED, PlayerFullscreenMinimized::class, PlayerFullscreenMinimized.serializer()),
        Entry(ProxyPacketType.BACKEND_DISPLAY_INDEX, BackendDisplayIndex::class, BackendDisplayIndex.serializer()),
    )

    private val byId = entries.associateBy { it.id }
    private val byType = entries.associateBy { it.type }

    init {
        require(byId.size == entries.size) { "Duplicate proxy packet type ids." }
        require(byType.size == entries.size) { "Duplicate proxy packet classes." }
        require(entries.map { it.packetType }.toSet() == ProxyPacketType.entries.toSet()) {
            "ProxyPacketRegistry must bind every ProxyPacketType exactly once."
        }
        entries.forEach { entry ->
            require(entry.type == entry.packetType.packetClass) {
                "Proxy packet type ${entry.packetType} is bound to ${entry.packetType.packetClass.simpleName}, " +
                        "but registry entry uses ${entry.type.simpleName}."
            }
        }
    }

    fun encode(packet: ProxyPacket): ByteArray {
        val entry = entryOf(packet)
        val payload = entry.encode(proto, packet)
        return proto.encodeToByteArray(ProxyEnvelope.serializer(), ProxyEnvelope(entry.id, payload))
    }

    fun decode(bytes: ByteArray): ProxyPacket? {
        val envelope = proto.decodeFromByteArray(ProxyEnvelope.serializer(), bytes)
        val entry = byId[envelope.type] ?: return null
        return proto.decodeFromByteArray(entry.serializer, envelope.payload)
    }

    fun decode(bytes: ByteArray, inbound: ProxyPacketDirection): ProxyPacket? {
        val packet = decode(bytes) ?: return null
        val direction = directionOf(packet)
        require(direction == inbound || direction == ProxyPacketDirection.BIDIRECTIONAL) {
            "Proxy packet ${packet::class.simpleName} travels $direction; not acceptable inbound as $inbound."
        }
        return packet
    }

    fun directionOf(packet: ProxyPacket): ProxyPacketDirection = entryOf(packet).direction

    private fun entryOf(packet: ProxyPacket): Entry<out ProxyPacket> =
        byType[packet::class] ?: error("Unregistered proxy packet type: ${packet::class.simpleName}.")
}
