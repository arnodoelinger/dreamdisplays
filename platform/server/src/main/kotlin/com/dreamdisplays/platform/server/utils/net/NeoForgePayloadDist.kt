package com.dreamdisplays.platform.server.utils.net

import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * `NeoForge` fires `RegisterPayloadHandlersEvent` on every dist, but a payload id can only be
 * registered once total for the whole mod.
 */
@NeoForgeOnly
val isClientDist: Boolean by lazy {
    (
            //? if >=1.21.11 {
            FMLEnvironment.getDist()
            //?} else
            /*FMLEnvironment.dist*/
            ) == Dist.CLIENT
}

/**
 * Wraps [handler] so it only ever runs on a genuine client process; a no-op everywhere else.
 * Building the lambda passed in is always safe (creating a Kotlin lambda never resolves classes
 * referenced only inside its body) - it's invoking it that would need `Initializer`'s class,
 * and this wrapper only invokes it when [isClientDist] is true.
 */
@NeoForgeOnly
fun <T : CustomPacketPayload> clientHandler(handler: (T, IPayloadContext) -> Unit): (T, IPayloadContext) -> Unit =
    if (isClientDist) handler else { _, _ -> }

/**
 * `PayloadRegistrar.playBidirectional` only takes separate server/client handlers from 1.21.11
 * onward; 1.21.1 has a single-handler overload, so the two are merged there by dispatching on
 * [IPayloadContext.flow] instead.
 */
@NeoForgeOnly
fun <T : CustomPacketPayload> PayloadRegistrar.playBidirectionalCompat(
    type: CustomPacketPayload.Type<T>,
    codec: StreamCodec<in RegistryFriendlyByteBuf, T>,
    serverHandler: (T, IPayloadContext) -> Unit,
    clientHandler: (T, IPayloadContext) -> Unit,
): PayloadRegistrar =
    //? if >=1.21.11 {
    playBidirectional(
        type, codec,
        { payload, context -> serverHandler(payload, context) },
        { payload, context -> clientHandler(payload, context) },
    )
//?} else
/*playBidirectional(type, codec) { payload, context ->
    if (context.flow() == PacketFlow.SERVERBOUND) serverHandler(payload, context) else clientHandler(payload, context)
}*/
