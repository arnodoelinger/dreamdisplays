package com.dreamdisplays.platform.client.net

import com.dreamdisplays.core.protocol.proxy.ProxyPacketRegistry
import com.dreamdisplays.platform.client.Initializer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Minecraft payload wrapper for the backend <-> proxy `dreamdisplays:proxy` channel (`Fabric` /
 * `NeoForge` only — `Paper` rides raw bytes over `Bukkit`'s plugin-messaging API instead, see
 * `ProxyBridge.kt`): opaque [ProxyPacketRegistry] envelope bytes, structurally identical to
 * [V2Payload] but on its own channel id so it can be registered independently.
 */
data class ProxyPayload(val bytes: ByteArray) : CustomPacketPayload {
    /** The payload type used by Minecraft's networking. */
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    /** Content-based equality over the envelope [bytes]. */
    override fun equals(other: Any?): Boolean = other is ProxyPayload && bytes.contentEquals(other.bytes)

    /** Content-based hash over the envelope [bytes]. */
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** The `dreamdisplays:proxy` channel name. */
        const val CHANNEL: String = "${Initializer.MOD_ID}:proxy"

        /** Registered payload type for the proxy channel. */
        val TYPE: CustomPacketPayload.Type<ProxyPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "proxy"))

        /** Stream codec that reads / writes the raw envelope bytes verbatim. */
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ProxyPayload> = StreamCodec.of(
            { buf, payload -> buf.writeBytes(payload.bytes) },
            { buf -> ProxyPayload(ByteArray(buf.readableBytes()).also(buf::readBytes)) },
        )
    }
}
