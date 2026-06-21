package com.dreamdisplays.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType
import java.util.UUID

/** UUID with all-zero bits, used as the wire default. */
val ZERO_UUID: UUID = UUID(0L, 0L)

/** Wire surrogate for [UUID]: two fixed64 halves (random bits make varint encoding wasteful). */
@Serializable @SerialName("Uuid")
@OptIn(ExperimentalSerializationApi::class)
private data class UuidSurrogate(
    @ProtoNumber(1) @ProtoType(ProtoIntegerType.FIXED) val msb: Long = 0L,
    @ProtoNumber(2) @ProtoType(ProtoIntegerType.FIXED) val lsb: Long = 0L,
)

/** Serializes [UUID] through [UuidSurrogate]; wire bytes are a two-field proto message. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = UuidSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeSerializableValue(
        UuidSurrogate.serializer(),
        UuidSurrogate(value.mostSignificantBits, value.leastSignificantBits),
    )

    override fun deserialize(decoder: Decoder): UUID =
        decoder.decodeSerializableValue(UuidSurrogate.serializer()).let { UUID(it.msb, it.lsb) }
}
