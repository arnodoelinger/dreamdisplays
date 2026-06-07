package com.dreamdisplays

import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.managers.ClientPacketManager
import com.dreamdisplays.managers.ClientShutdownManager
import com.dreamdisplays.managers.ClientStartupManager
import com.dreamdisplays.managers.ClientTickManager
import com.dreamdisplays.managers.DisplayLifecycleManager
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/** Main mod initializer. */
object Initializer {
    const val MOD_ID: String = "dreamdisplays"
    private val logger = LoggerFactory.getLogger("DreamDisplays/Initializer")

    /** Called once during mod startup; initializes config, `yt-dlp`, `FFmpeg`, disk cache, and the focuser thread. */
    fun onModInit(dreamDisplaysMod: Mod) {
        ClientPacketManager.bind(dreamDisplaysMod)

        logger.info("Starting Dream Displays...")
        ClientStartupManager.start()
    }

    /** Handles an incoming [Packets.Info] packet: updates an existing screen or creates a new one if within render distance. */
    fun onDisplayInfoPacket(packet: Packets.Info) {
        DisplayLifecycleManager.handleInfoPacket(packet)
    }

    /** Toggles global display rendering on / off as instructed by the server. */
    fun onDisplayEnabledPacket(packet: Packets.DisplayEnabled) {
        ClientPacketManager.handleDisplayEnabled(packet)
    }

    /** Forwards a [Packets.Sync] packet to the matching display screen. */
    fun onSyncPacket(packet: Packets.Sync) {
        ClientPacketManager.handleSync(packet)
    }

    /**
     * Main client tick handler. Detects level changes, manages render-distance unloading / restoring,
     * handles the right-click shortcut, and applies focus-mode blindness.
     */
    fun onEndTick(minecraft: Minecraft) {
        ClientTickManager.tick(minecraft)
    }

    /** Renders all active PiP overlays on the HUD when the player is in-world and no screen is open. */
    fun onRenderHud(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        if (mc.level == null || mc.player == null) return
        if (MinecraftScreenUtil.currentScreen(mc) != null) return
        PipOverlayManager.renderAll(mc, graphics, -1, -1, false, partialTick)
    }

    /** Delegates packet sending to the platform-specific [Mod] implementation. */
    fun sendPacket(packet: CustomPacketPayload) {
        ClientPacketManager.send(packet)
    }

    /** Unregisters and removes all data for the display identified by [Packets.Delete.uuid]. */
    fun onDeletePacket(packet: Packets.Delete) {
        ClientPacketManager.handleDelete(packet)
    }

    /** Saves screen data to disk, stops all players, and interrupts background threads on mod shutdown. */
    fun onStop() {
        ClientShutdownManager.stop()
    }

    /** Updates the local premium flag from a server [Packets.Premium] packet. */
    fun onPremiumPacket(packet: Packets.Premium) {
        ClientPacketManager.handlePremium(packet)
    }

    /** Updates the local admin flag from a server [Packets.IsAdmin] packet. */
    fun onIsAdminPacket(packet: Packets.IsAdmin) {
        ClientPacketManager.handleIsAdmin(packet)
    }

    /** Enables or disables the reporting feature based on a server [Packets.ReportEnabled] packet. */
    fun onReportEnabledPacket(packet: Packets.ReportEnabled) {
        ClientPacketManager.handleReportEnabled(packet)
    }

    /** Unregisters and clears saved data for the UUIDs listed in a [Packets.ClearCache] packet. */
    fun onClearCachePacket(packet: Packets.ClearCache) {
        ClientPacketManager.handleClearCache(packet)
    }
}
