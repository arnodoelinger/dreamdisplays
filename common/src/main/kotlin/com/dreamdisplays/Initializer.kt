package com.dreamdisplays

import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.ClientLifecycleEvent
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplays.displays.DisplayManager
import com.dreamdisplays.managers.ClientPacketManager
import com.dreamdisplays.managers.ClientStateManager
import com.dreamdisplays.managers.ClientShutdownManager
import com.dreamdisplays.managers.ClientStartupManager
import com.dreamdisplays.managers.ClientTickManager
import com.dreamdisplays.managers.DisplayLifecycleManager
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/** Main mod initializer. */
object Initializer {
    const val MOD_ID: String = "dreamdisplays"
    private val logger = LoggerFactory.getLogger("DreamDisplays/Initializer")

    /** Called once during mod startup; initializes config, `yt-dlp`, `FFmpeg`, disk cache, and the focuser thread. */
    fun onModInit(dreamDisplaysMod: Mod) {
        // On macOS, VideoPopoutWindow uses GLFW (not AWT), so no AWT setup is needed.
        // On Windows / Linux, AWT is used: override java.awt.headless so a JFrame can open.
        // Must run before any AWT class initialises the Toolkit.
        if (!System.getProperty("os.name", "").lowercase().startsWith("mac")) {
            System.setProperty("java.awt.headless", "false")
        }
        ClientPacketManager.bind(dreamDisplaysMod)

        logger.info("Starting Dream Displays...")
        ClientStartupManager.start()
    }

    /**
     * Called by the platform entrypoint after joining a server: records [serverId] in the client
     * state, restores saved screens, and emits [ClientLifecycleEvent.ServerJoined].
     */
    fun onServerJoined(serverId: String) {
        ClientStateManager.connectedServerId = serverId
        DisplayManager.loadScreensForServer(serverId)
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.ServerJoined(serverId))
    }

    /**
     * Called by the platform entrypoint on disconnect: persists and unloads all screens, resets
     * the per-server flags, and emits [ClientLifecycleEvent.ServerLeft].
     */
    fun onServerLeft() {
        val serverId = ClientStateManager.connectedServerId
        DisplayManager.saveAllScreens()
        DisplayManager.unloadAll()
        ClientStateManager.isPremium = false
        ClientStateManager.isAdmin = false
        ClientStateManager.connectedServerId = null
        if (serverId != null) {
            DreamServices.registry.getOrNull<ClientApplication>()
                ?.emit(ClientLifecycleEvent.ServerLeft(serverId))
        }
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
    //? if >=26 {
    fun onRenderHud(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
    //?} else
    /*fun onRenderHud(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {*/
        if (mc.level == null || mc.player == null) return
        if (MinecraftScreenUtil.currentScreen(mc) != null) return
        DreamServices.registry.getOrNull<OverlayManager>()
            ?.renderAll(MinecraftOverlayRenderContext(mc, graphics, -1, -1, false, partialTick))
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
