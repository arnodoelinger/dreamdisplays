package com.dreamdisplays.platform.client.net

import com.dreamdisplays.platform.client.Initializer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The single Minecraft payload wrapper for protocol v2: opaque [PacketRegistry][com.dreamdisplays.protocol.PacketRegistry]
 * envelope bytes on the `dreamdisplays:v2` channel. All structure lives in the platform-free
 * `:protocol` module.
 */
data class V2Payload(val bytes: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    override fun equals(other: Any?): Boolean = other is V2Payload && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val CHANNEL: String = "${Initializer.MOD_ID}:v2"
        val TYPE: CustomPacketPayload.Type<V2Payload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "v2"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, V2Payload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> V2Payload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}
