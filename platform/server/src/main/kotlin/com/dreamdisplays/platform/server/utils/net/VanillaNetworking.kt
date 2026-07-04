package com.dreamdisplays.platform.server.utils.net

import com.dreamdisplays.core.protocol.DreamPacket
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The one genuinely loader-specific seam left after merging the rest of `Fabric` / `NeoForge`
 * packet handling: how a v2 envelope and a frozen-v1 [CustomPacketPayload] actually get put on the
 * wire.
 *
 * [VanillaPacketUtil] and other shared vanilla code call through [VanillaNetworking.adapter]
 * instead of hardcoding either loader's send API, mirroring [com.dreamdisplays.platform.server.scheduler.ProviderScheduler].
 */
interface VanillaNetworkingAdapter {
    /** Sends a v2 envelope [packet] to [players] via this loader's v2 channel. */
    fun sendV2(players: List<ServerPlayer>, packet: DreamPacket)

    /** Sends a frozen-v1 [packet] to a single [player]. */
    fun sendLegacy(player: ServerPlayer, packet: CustomPacketPayload)
}

/** Holds the active [VanillaNetworkingAdapter], set once by whichever vanilla loader is running. */
object VanillaNetworking {
    lateinit var adapter: VanillaNetworkingAdapter
}

/** `Fabric` [VanillaNetworkingAdapter]: v2 via [FabricV2Networking], legacy via `ServerPlayNetworking`. */
@FabricOnly
object FabricNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) {
        FabricV2Networking.send(players, packet)
    }

    override fun sendLegacy(player: ServerPlayer, packet: CustomPacketPayload) {
        runCatching { ServerPlayNetworking.send(player, packet) }
    }
}

/** `NeoForge` [VanillaNetworkingAdapter]: v2 via [NeoForgeV2Networking], legacy via `PacketDistributor`. */
@NeoForgeOnly
object NeoForgeNetworkingAdapter : VanillaNetworkingAdapter {
    override fun sendV2(players: List<ServerPlayer>, packet: DreamPacket) {
        NeoForgeV2Networking.send(players, packet)
    }

    override fun sendLegacy(player: ServerPlayer, packet: CustomPacketPayload) {
        runCatching { PacketDistributor.sendToPlayer(player, packet) }
    }
}
