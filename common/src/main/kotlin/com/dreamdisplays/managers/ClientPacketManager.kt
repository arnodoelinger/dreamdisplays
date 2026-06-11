package com.dreamdisplays.managers

import com.dreamdisplays.Mod
import com.dreamdisplays.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.store.DisplayStorage
import com.dreamdisplays.net.Packets
import com.dreamdisplays.protocol.ServerCapabilities
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/**
 * Handles client packet state changes and delegates outgoing packets to the platform implementation.
 */
// TODO: rewrite
object ClientPacketManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientPacketManager")

    private lateinit var mod: Mod

    fun bind(mod: Mod) {
        this.mod = mod
    }

    fun send(packet: CustomPacketPayload) {
        mod.sendPacket(packet)
    }

    fun handleDisplayEnabled(packet: Packets.DisplayEnabled) {
        ClientStateManager.displaysEnabled = packet.enabled
        ClientStateManager.config.displaysEnabled = packet.enabled
        ClientStateManager.config.save()
    }

    fun handleSync(packet: Packets.Sync) {
        DisplayRegistry.screens[packet.uuid]?.updateData(packet)
    }

    fun handleDelete(packet: Packets.Delete) {
        DisplayRegistry.screens[packet.uuid]?.let { DisplayRegistry.unregisterScreen(it) }
        DisplayRegistry.unloadedScreens.remove(packet.uuid)
        DisplayStorage.removeDisplay(packet.uuid)
        logger.info("Display deleted and removed from saved data: ${packet.uuid}.")
    }

    fun handlePremium(packet: Packets.Premium) {
        ClientStateManager.isPremium = packet.premium
        mergeServerCapabilities { it.copy(isPremium = packet.premium) }
    }

    fun handleIsAdmin(packet: Packets.IsAdmin) {
        ClientStateManager.isAdmin = packet.isAdmin
    }

    fun handleReportEnabled(packet: Packets.ReportEnabled) {
        ClientStateManager.isReportingEnabled = packet.enabled
        mergeServerCapabilities { it.copy(isReportingEnabled = packet.enabled) }
    }

    /** Folds a legacy handshake flag into the [CapabilityNegotiationService] server snapshot. */
    private fun mergeServerCapabilities(transform: (ServerCapabilities) -> ServerCapabilities) {
        val service = DreamServices.registry.getOrNull<CapabilityNegotiationService>() ?: return
        service.onServerCapabilities(transform(service.serverCapabilities ?: ServerCapabilities()))
    }

    fun handleClearCache(packet: Packets.ClearCache) {
        packet.displayUuids.forEach { uuid ->
            DisplayRegistry.screens.remove(uuid)?.unregister()
            DisplayStorage.removeDisplay(uuid)
        }
    }
}
