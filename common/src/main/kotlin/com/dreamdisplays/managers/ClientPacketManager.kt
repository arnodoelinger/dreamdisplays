package com.dreamdisplays.managers

import com.dreamdisplays.Mod
import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplaySettings
import com.dreamdisplays.net.Packets
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/**
 * Handles client packet state changes and delegates outgoing packets to the platform implementation.
 */
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
        DisplayManager.screens[packet.uuid]?.updateData(packet)
    }

    fun handleDelete(packet: Packets.Delete) {
        DisplayManager.screens[packet.uuid]?.let { DisplayManager.unregisterScreen(it) }
        DisplayManager.unloadedScreens.remove(packet.uuid)
        DisplaySettings.removeDisplay(packet.uuid)
        logger.info("Display deleted and removed from saved data: ${packet.uuid}.")
    }

    fun handlePremium(packet: Packets.Premium) {
        ClientStateManager.isPremium = packet.premium
    }

    fun handleIsAdmin(packet: Packets.IsAdmin) {
        ClientStateManager.isAdmin = packet.isAdmin
    }

    fun handleReportEnabled(packet: Packets.ReportEnabled) {
        ClientStateManager.isReportingEnabled = packet.enabled
    }

    fun handleClearCache(packet: Packets.ClearCache) {
        packet.displayUuids.forEach { uuid ->
            DisplayManager.screens.remove(uuid)?.unregister()
            DisplaySettings.removeDisplay(uuid)
        }
    }
}
