@file:OptIn(ExperimentalSerializationApi::class)

package com.dreamdisplays.core.protocol


import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.reflect.KClass

/** Wire frame for the `dreamdisplays:v2` channel: a type id plus the encoded packet bytes. */
@Serializable data class Envelope(
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
        val id: Int,
        val type: KClass<T>,
        val serializer: KSerializer<T>,
        val direction: PacketDirection,
    )

    private val entries: List<Entry<out DreamPacket>> = listOf(
        Entry(1, ClientHello::class, ClientHello.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(2, ServerHello::class, ServerHello.serializer(), PacketDirection.SERVER_TO_CLIENT),
        Entry(3, DisplayInfo::class, DisplayInfo.serializer(), PacketDirection.SERVER_TO_CLIENT),
        Entry(4, DisplayDelete::class, DisplayDelete.serializer(), PacketDirection.BIDIRECTIONAL),
        Entry(5, DisplaySync::class, DisplaySync.serializer(), PacketDirection.BIDIRECTIONAL),
        Entry(6, RequestSync::class, RequestSync.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(7, SetVideo::class, SetVideo.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(8, SetLocked::class, SetLocked.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(9, ReportDisplay::class, ReportDisplay.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(10, SetDisplaysEnabled::class, SetDisplaysEnabled.serializer(), PacketDirection.BIDIRECTIONAL),
        Entry(11, ClearCache::class, ClearCache.serializer(), PacketDirection.SERVER_TO_CLIENT),
        Entry(12, PlaybackCommand::class, PlaybackCommand.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(13, SetMode::class, SetMode.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(14, WatchPartyStart::class, WatchPartyStart.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(15, WatchPartyControl::class, WatchPartyControl.serializer(), PacketDirection.CLIENT_TO_SERVER),
        Entry(16, WatchPartyState::class, WatchPartyState.serializer(), PacketDirection.SERVER_TO_CLIENT),
    )

    private val byId = entries.associateBy { it.id }
    private val byType = entries.associateBy { it.type }

    init {
        require(byId.size == entries.size) { "Duplicate packet type ids." }
        require(byType.size == entries.size) { "Duplicate packet classes." }
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

    /** The registered travel direction of [packet]'s type. */
    fun directionOf(packet: DreamPacket): PacketDirection = entryOf(packet).direction

    /** Descriptors of every registered packet, in id order; feeds the .proto schema generator. */
    val schemaDescriptors: List<SerialDescriptor>
        get() = entries.map { it.serializer.descriptor }

    private fun entryOf(packet: DreamPacket): Entry<out DreamPacket> =
        byType[packet::class] ?: error("Unregistered packet type: ${packet::class.simpleName}.")
}
