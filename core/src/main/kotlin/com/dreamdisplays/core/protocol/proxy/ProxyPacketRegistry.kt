@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol.proxy

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.reflect.KClass

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

/**
 * Maps append-only [ProxyPacketType] ids to packet serializers for the `dreamdisplays:proxy`
 * channel. Mirrors [com.dreamdisplays.core.protocol.PacketRegistry] exactly, but kept as a fully
 * separate registry / id-space / envelope: [com.dreamdisplays.core.protocol.PacketRegistry]'s init
 * block requires every [com.dreamdisplays.core.protocol.ProtocolPacketType] to be bound and its
 * `decode(bytes, inbound)` overload validates against client/server directions that are meaningless
 * for a proxy link — reusing it here would either fail those invariants or let a client decode
 * proxy-only packets.
 *
 * Only direct generated `X.serializer()` references are allowed here — reflection-based lookup
 * breaks under shadow relocation (same constraint as `PacketRegistry`).
 */
object ProxyPacketRegistry {
    private val proto = ProtoBuf { encodeDefaults = false }

    private class Entry<T : ProxyPacket>(
        val packetType: ProxyPacketType,
        val type: KClass<T>,
        val serializer: KSerializer<T>,
    ) {
        val id: Int get() = packetType.id
        val direction: ProxyPacketDirection get() = packetType.direction
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

    /** Encodes [packet] into envelope bytes ready for the `dreamdisplays:proxy` channel. */
    fun encode(packet: ProxyPacket): ByteArray {
        val entry = entryOf(packet)

        @Suppress("UNCHECKED_CAST")
        val payload = proto.encodeToByteArray(entry.serializer as KSerializer<ProxyPacket>, packet)
        return proto.encodeToByteArray(ProxyEnvelope.serializer(), ProxyEnvelope(entry.id, payload))
    }

    /** Decodes envelope bytes; returns null for unknown type ids (newer peer, skip silently). */
    fun decode(bytes: ByteArray): ProxyPacket? {
        val envelope = proto.decodeFromByteArray(ProxyEnvelope.serializer(), bytes)
        val entry = byId[envelope.type] ?: return null
        return proto.decodeFromByteArray(entry.serializer, envelope.payload)
    }

    /**
     * Decodes envelope bytes for a receiver that legitimately accepts packets travelling [inbound]
     * (the proxy passes [ProxyPacketDirection.BACKEND_TO_PROXY], a backend passes
     * [ProxyPacketDirection.PROXY_TO_BACKEND]).
     *
     * Unknown type ids return null as in [decode]; a packet whose registered direction does not
     * match (and is not [ProxyPacketDirection.BIDIRECTIONAL]) throws, surfacing a wrongly-wired
     * handler instead of letting the receiver act on a packet meant for the other side.
     */
    fun decode(bytes: ByteArray, inbound: ProxyPacketDirection): ProxyPacket? {
        val packet = decode(bytes) ?: return null
        val direction = directionOf(packet)
        require(direction == inbound || direction == ProxyPacketDirection.BIDIRECTIONAL) {
            "Proxy packet ${packet::class.simpleName} travels $direction; not acceptable inbound as $inbound."
        }
        return packet
    }

    /** The registered travel direction of [packet]'s type. */
    fun directionOf(packet: ProxyPacket): ProxyPacketDirection = entryOf(packet).direction

    private fun entryOf(packet: ProxyPacket): Entry<out ProxyPacket> =
        byType[packet::class] ?: error("Unregistered proxy packet type: ${packet::class.simpleName}.")
}
