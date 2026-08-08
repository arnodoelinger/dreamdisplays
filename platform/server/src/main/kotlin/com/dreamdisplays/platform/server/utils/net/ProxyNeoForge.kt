package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.platform.client.net.ProxyPayload
import com.dreamdisplays.platform.server.proxy.VanillaProxyBridge
import com.dreamdisplays.platform.server.utils.RegionUtil
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import org.slf4j.LoggerFactory

/**
 * `dreamdisplays:proxy` networking for the `NeoForge` flavor. Mirrors [NeoForgeV2Networking]'s
 * shape, but for the backend <-> proxy channel instead of the player-facing v2 one.
 */
@NeoForgeOnly
object NeoForgeProxyNetworking {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/NeoForgeProxyNetworking")

    /**
     * Registers the single proxy envelope receiver against [registrar]. Must be called exactly once
     * total for the whole mod, alongside [NeoForgeV2Networking.registerReceivers] on the same
     * `registrar`.
     */
    fun registerReceivers(registrar: PayloadRegistrar) {
        registrar.playBidirectionalCompat(
            ProxyPayload.TYPE, ProxyPayload.CODEC,
            serverHandler = { payload, context ->
                runCatching {
                    val player = context.player() as ServerPlayer
                    VanillaProxyBridge.onMessage(player, RegionUtil.playerServer(player), payload.bytes)
                }.onFailure { e -> logger.warn("Failed to handle proxy packet.", e) }
            },
            clientHandler = { _, _ -> },
        )
    }
}
