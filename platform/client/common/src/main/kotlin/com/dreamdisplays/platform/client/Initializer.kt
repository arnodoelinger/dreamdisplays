package com.dreamdisplays.platform.client

import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.ClientLifecycleEvent
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.core.getOrNull
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.ui.MinecraftOverlayRenderContext
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.managers.ClientPacketManager
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.managers.ClientShutdownManager
import com.dreamdisplays.platform.client.managers.ClientStartupManager
import com.dreamdisplays.platform.client.managers.ClientTickManager
import com.dreamdisplays.platform.client.managers.DisplayLifecycleManager
import com.dreamdisplays.platform.client.net.LegacyAdapter
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.core.protocol.DreamPacket
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.slf4j.LoggerFactory

/** Main mod initializer. */
object Initializer {
    /** The mod identifier, used for channels, resources, and registration. */
    const val MOD_ID: String = "dreamdisplays"

    /** Logger for startup and lifecycle messages. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/Initializer")

    /** Called once during mod startup; initializes config, `yt-dlp`, `FFmpeg`, disk cache, and the focuser thread. */
    fun onModInit(dreamDisplaysMod: Mod) {
        // On macOS, VideoPopoutWindow uses GLFW (not AWT), so no AWT setup is needed.
        // On Windows / Linux, AWT is used: override java.awt.headless so a JFrame can open.
        // Must run before any AWT class initializes the Toolkit.
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
        DisplayRegistry.loadScreensForServer(serverId)
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.ServerJoined(serverId))
    }

    /**
     * Called by the platform entrypoint on disconnect: persists and unloads all screens, resets
     * the per-server flags, and emits [ClientLifecycleEvent.ServerLeft].
     */
    fun onServerLeft() {
        val serverId = ClientStateManager.connectedServerId
        DisplayRegistry.saveAllScreens()
        DisplayRegistry.unloadAll()
        ClientStateManager.isPremium = false
        ClientStateManager.isAdmin = false
        ClientStateManager.connectedServerId = null
        ProtocolRouter.reset()
        ClientPacketManager.reset()
        if (serverId != null) {
            DreamServices.registry.getOrNull<ClientApplication>()
                ?.emit(ClientLifecycleEvent.ServerLeft(serverId))
        }
    }

    /** Lifts an incoming frozen-v1 [payload] into its v2 packet and dispatches it. */
    fun onLegacyPacket(payload: CustomPacketPayload) {
        ProtocolRouter.onLegacyReceived(LegacyAdapter.fromLegacy(payload))
    }

    /** Decodes and dispatches v2 envelope [bytes] from the `dreamdisplays:v2` channel. */
    fun onV2Packet(bytes: ByteArray) {
        ProtocolRouter.onV2Received(bytes)
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

    /** Routes an outgoing [packet] through protocol negotiation (v2 when available, else v1). */
    fun sendPacket(packet: DreamPacket) {
        ProtocolRouter.send(packet)
    }

    /** Saves screen data to disk, stops all players, and interrupts background threads on mod shutdown. */
    fun onStop() {
        ClientShutdownManager.stop()
    }
}
