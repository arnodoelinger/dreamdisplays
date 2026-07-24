@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol

import com.dreamdisplays.api.protocol.PacketDirection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.reflect.KClass

/** Wire frame for the `dreamdisplays:v2` channel: a type id plus the encoded packet bytes. */
@Serializable
data class Envelope(
    @ProtoNumber(1) val type: Int = 0,
    @ProtoNumber(2) val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is Envelope && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type + payload.contentHashCode()
}

/**
 * Maps append-only type ids to packet serializers. Encoding wraps the packet into an [Envelope];
 * decoding an unknown type id returns null (forward compatibility with newer peers).
 *
 * Only direct generated `X.serializer()` references are allowed here — reflection-based lookup
 * (`serializer(typeOf<...>())`, `serializersModule`) breaks under shadow relocation.
 */
object PacketRegistry {
    private val proto = ProtoBuf { encodeDefaults = false }

    private class Entry<T : DreamPacket>(
        val packetType: ProtocolPacketType,
        val type: KClass<T>,
        val serializer: KSerializer<T>,
    ) {
        val id: Int get() = packetType.id
        val direction: PacketDirection get() = packetType.direction
    }

    private val entries: List<Entry<out DreamPacket>> = listOf(
        Entry(ProtocolPacketType.CLIENT_HELLO, ClientHello::class, ClientHello.serializer()),
        Entry(ProtocolPacketType.SERVER_HELLO, ServerHello::class, ServerHello.serializer()),
        Entry(ProtocolPacketType.DISPLAY_INFO, DisplayInfo::class, DisplayInfo.serializer()),
        Entry(ProtocolPacketType.DISPLAY_DELETE, DisplayDelete::class, DisplayDelete.serializer()),
        Entry(ProtocolPacketType.DISPLAY_SYNC, DisplaySync::class, DisplaySync.serializer()),
        Entry(ProtocolPacketType.REQUEST_SYNC, RequestSync::class, RequestSync.serializer()),
        Entry(ProtocolPacketType.SET_VIDEO, SetVideo::class, SetVideo.serializer()),
        Entry(ProtocolPacketType.SET_LOCKED, SetLocked::class, SetLocked.serializer()),
        Entry(ProtocolPacketType.REPORT_DISPLAY, ReportDisplay::class, ReportDisplay.serializer()),
        Entry(ProtocolPacketType.SET_DISPLAYS_ENABLED, SetDisplaysEnabled::class, SetDisplaysEnabled.serializer()),
        Entry(ProtocolPacketType.CLEAR_CACHE, ClearCache::class, ClearCache.serializer()),
        Entry(ProtocolPacketType.PLAYBACK_COMMAND, PlaybackCommand::class, PlaybackCommand.serializer()),
        Entry(ProtocolPacketType.SET_MODE, SetMode::class, SetMode.serializer()),
        Entry(ProtocolPacketType.WATCH_PARTY_START, WatchPartyStart::class, WatchPartyStart.serializer()),
        Entry(ProtocolPacketType.WATCH_PARTY_CONTROL, WatchPartyControl::class, WatchPartyControl.serializer()),
        Entry(ProtocolPacketType.WATCH_PARTY_STATE, WatchPartyState::class, WatchPartyState.serializer()),
        Entry(ProtocolPacketType.FULLSCREEN_STATE, FullscreenState::class, FullscreenState.serializer()),
        Entry(ProtocolPacketType.FULLSCREEN_ACK, FullscreenAck::class, FullscreenAck.serializer()),
        Entry(ProtocolPacketType.RADIUS_PREVIEW, RadiusPreview::class, RadiusPreview.serializer()),
        Entry(ProtocolPacketType.PIP_PIN, PipPin::class, PipPin.serializer()),
        Entry(ProtocolPacketType.REPORT_DURATION, ReportDuration::class, ReportDuration.serializer()),
    )

    private val byId = entries.associateBy { it.id }
    private val byType = entries.associateBy { it.type }

    init {
        require(byId.size == entries.size) { "Duplicate packet type ids." }
        require(byType.size == entries.size) { "Duplicate packet classes." }
        require(entries.map { it.packetType }.toSet() == ProtocolPacketType.entries.toSet()) {
            "PacketRegistry must bind every ProtocolPacketType exactly once."
        }
        entries.forEach { entry ->
            require(entry.type == entry.packetType.packetClass) {
                "Packet type ${entry.packetType} is bound to ${entry.packetType.packetClass.simpleName}, " +
                        "but registry entry uses ${entry.type.simpleName}."
            }
        }
    }

    /** Encodes [packet] into envelope bytes ready for the `dreamdisplays:v2` channel. */
    fun encode(packet: DreamPacket): ByteArray {
        val entry = entryOf(packet)

        @Suppress("UNCHECKED_CAST")
        val payload = proto.encodeToByteArray(entry.serializer as KSerializer<DreamPacket>, packet)
        return proto.encodeToByteArray(Envelope.serializer(), Envelope(entry.id, payload))
    }

    /** Decodes envelope bytes; returns null for unknown type ids (newer peer, skip silently). */
    fun decode(bytes: ByteArray): DreamPacket? {
        val envelope = proto.decodeFromByteArray(Envelope.serializer(), bytes)
        val entry = byId[envelope.type] ?: return null
        return proto.decodeFromByteArray(entry.serializer, envelope.payload)
    }

    /**
     * Decodes envelope bytes for a receiver that legitimately accepts packets travelling [inbound]
     * (servers pass [PacketDirection.CLIENT_TO_SERVER]; clients pass [PacketDirection.SERVER_TO_CLIENT]).
     *
     * Unknown type ids return null as in [decode]; a packet whose registered direction does not match
     * (and is not [PacketDirection.BIDIRECTIONAL]) throws, surfacing a wrongly-wired handler instead
     * of letting the receiver act on a packet meant for the other side.
     */
    fun decode(bytes: ByteArray, inbound: PacketDirection): DreamPacket? {
        val packet = decode(bytes) ?: return null
        val direction = directionOf(packet)
        require(direction == inbound || direction == PacketDirection.BIDIRECTIONAL) {
            "Packet ${packet::class.simpleName} travels $direction; not acceptable inbound as $inbound."
        }
        return packet
    }

    /** The registered travel direction of [packet]'s type. */
    fun directionOf(packet: DreamPacket): PacketDirection = entryOf(packet).direction

    /** Descriptors of every registered packet, in id order; feeds the .proto schema generator. */
    val schemaDescriptors: List<SerialDescriptor>
        get() = entries.map { it.serializer.descriptor }

    private fun entryOf(packet: DreamPacket): Entry<out DreamPacket> =
        byType[packet::class] ?: error("Unregistered packet type: ${packet::class.simpleName}.")
}
