package com.dreamdisplays.api.storage

import com.dreamdisplays.api.DreamDisplaysUnstableApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.*

/**
 * JSON-friendly UUID serializer used by persisted settings and display snapshots.
 *
 * @since 1.8.4
 */
@DreamDisplaysUnstableApi
object UuidStringSerializer : KSerializer<UUID> {
    /** Descriptor for this serializer. */
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.dreamdisplays.UUID", PrimitiveKind.STRING)

    /** Serializes a [UUID] to a string. */
    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    /** Deserializes a string to a [UUID]. */
    override fun deserialize(decoder: Decoder): UUID =
        UUID.fromString(decoder.decodeString())
}
