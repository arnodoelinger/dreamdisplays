package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.platform.client.Mod
import com.dreamdisplays.application.display.DisplaySystem
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.core.display.DisplayId
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.storage.DisplayStorage
import com.dreamdisplays.protocol.ClearCache
import com.dreamdisplays.protocol.DisplayDelete
import com.dreamdisplays.protocol.DisplayInfo
import com.dreamdisplays.protocol.DisplaySync
import com.dreamdisplays.protocol.DreamPacket
import com.dreamdisplays.protocol.ServerHello
import com.dreamdisplays.protocol.SetDisplaysEnabled
import com.dreamdisplays.protocol.WatchPartyState
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/**
 * Single client-side dispatcher for incoming [DreamPacket]s (v2 and legacy-lifted alike) and the
 * raw payload sender bound to the platform [Mod] implementation.
 */
object ClientPacketManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientPacketManager")

    private lateinit var mod: Mod

    /** The latest applied [ServerHello]; legacy per-flag packets merge into this snapshot. */
    @Volatile
    var serverSnapshot: ServerHello = ServerHello()
        private set

    fun bind(mod: Mod) {
        this.mod = mod
    }

    /** Sends a raw payload through the platform networking implementation. */
    fun send(packet: CustomPacketPayload) {
        mod.sendPacket(packet)
    }

    /** Applies an incoming packet to the client state; non-clientbound packets are ignored. */
    fun handle(packet: DreamPacket) {
        when (packet) {
            is ServerHello -> applyServerHello(packet)
            is SetDisplaysEnabled -> applyDisplaysEnabled(packet.enabled)
            is DisplayInfo -> DisplayLifecycleManager.handleInfoPacket(packet)
            is DisplaySync -> DisplayRegistry.screens[packet.id]?.let {
                it.updateData(packet)
                DisplayRegistry.recordScreen(it)
            }
            is WatchPartyState -> DisplayRegistry.screens[packet.id]?.let {
                it.updateWatchParty(packet)
                DisplayRegistry.recordScreen(it)
            }
            is DisplayDelete -> handleDelete(packet)
            is ClearCache -> handleClearCache(packet)
            else -> logger.debug("Ignoring non-clientbound packet {}.", packet::class.simpleName)
        }
    }

    /** Replaces the capability snapshot wholesale and mirrors the flags into the client state. */
    private fun applyServerHello(packet: ServerHello) {
        serverSnapshot = packet
        ClientStateManager.isPremium = packet.isPremium
        ClientStateManager.isAdmin = packet.isAdmin
        ClientStateManager.isReportingEnabled = packet.isReportingEnabled
        DreamServices.registry.getOrNull<CapabilityNegotiationService>()
            ?.onServerCapabilities(packet)
    }

    /** Server-forced display toggle (admin command), persisted like the legacy channel did. */
    private fun applyDisplaysEnabled(enabled: Boolean) {
        ClientStateManager.displaysEnabled = enabled
        ClientStateManager.config.displaysEnabled = enabled
        ClientStateManager.config.save()
    }

    private fun handleDelete(packet: DisplayDelete) {
        DisplayRegistry.screens[packet.id]?.let { DisplayRegistry.unregisterScreen(it) }
        DisplayRegistry.unloadedScreens.remove(packet.id)
        DisplayStorage.removeDisplay(packet.id)
        logger.info("Display deleted and removed from saved data: ${packet.id}.")
    }

    private fun handleClearCache(packet: ClearCache) {
        packet.ids.forEach { uuid ->
            DisplayRegistry.screens.remove(uuid)?.unregister()
            DreamServices.registry.getOrNull<DisplaySystem>()?.removeDisplay(DisplayId(uuid))
            DisplayStorage.removeDisplay(uuid)
        }
    }

    /** Resets per-server negotiation state on disconnect. */
    fun reset() {
        serverSnapshot = ServerHello()
    }
}
