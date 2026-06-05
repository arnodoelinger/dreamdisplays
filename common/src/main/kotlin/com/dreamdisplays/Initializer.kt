package com.dreamdisplays

import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.display.DisplaySettings
import com.dreamdisplays.ffmpeg.FFmpegBinary
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.FacingUtil
import com.dreamdisplays.utils.GeneralUtil
import com.dreamdisplays.utils.RayCastingUtil
import com.dreamdisplays.ytdlp.FormatDiskCache
import com.dreamdisplays.ytdlp.YtDlp
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import org.joml.Vector3i
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import kotlin.math.sqrt

/** Main mod initializer. */
object Initializer {
    const val MOD_ID: String = "dreamdisplays"
    private val logger = LoggerFactory.getLogger("DreamDisplays/Initializer")

    private var wasPressed = false
    private var wasInMultiplayer = false
    @Volatile private var lastLevel: ClientLevel? = null
    private var wasFocused = false

    var config: Config = Config(File("./config/$MOD_ID"))

    /** Background thread that periodically re-evaluates quality settings for all active screens. */
    val timerThread: Thread = Thread({
        var running = true
        while (running) {
            DisplayManager.getScreens().forEach(DisplayScreen::reloadQuality)
            try {
                Thread.sleep(2500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                running = false
            }
        }
    }, "dreamdisplays-quality-refresh")

    var isOnScreen: Boolean = false
    var focusMode: Boolean = false
    var displaysEnabled: Boolean = true
    var isPremium: Boolean = false
    var isAdmin: Boolean = false
    var isReportingEnabled: Boolean = true

    private var unloadCheckTick: Int = 0
    private var hoveredDisplayScreen: DisplayScreen? = null
    private lateinit var mod: Mod

    /** Called once during mod startup; initializes config, `yt-dlp`, `FFmpeg`, disk cache, and the focuser thread. */
    fun onModInit(dreamDisplaysMod: Mod) {
        mod = dreamDisplaysMod
        logger.info("Starting Dream Displays...")
        config.reload()

        DisplaySettings.load()

        YtDlp.prewarmAsync()
        FFmpegBinary.prewarmAsync()
        Thread({ FormatDiskCache.sweepExpired() }, "dreamdisplays-cache-sweep").start()
        Focuser().start()

        timerThread.start()
    }

    /** Handles an incoming [Packets.Info] packet: updates an existing screen or creates a new one if within render distance. */
    fun onDisplayInfoPacket(packet: Packets.Info) {
        if (!displaysEnabled) return

        DisplayManager.screens[packet.uuid]?.let {
            it.updateData(packet)
            return
        }

        Minecraft.getInstance().player?.let { player ->
            val renderDistance = DisplaySettings.getDisplayData(packet.uuid)?.renderDistance ?: config.defaultDistance
            val dist = distanceToScreen(
                packet.pos.x, packet.pos.y, packet.pos.z,
                packet.width, packet.height, packet.facingUtil.toString(),
                player.blockPosition()
            )
            if (dist > renderDistance) return
        }

        YtDlp.prefetchFormats(packet.url)
        DisplayManager.unloadedScreens.remove(packet.uuid)

        createScreen(
            packet.uuid, packet.ownerUuid, packet.pos, packet.facingUtil,
            packet.width, packet.height, packet.url, packet.lang, packet.isSync
        )
    }

    /** Toggles global display rendering on / off as instructed by the server. */
    fun onDisplayEnabledPacket(packet: Packets.DisplayEnabled) {
        displaysEnabled = packet.enabled
        config.displaysEnabled = packet.enabled
        config.save()
    }

    /** Constructs a [DisplayScreen], registers it, and starts video playback if a URL is provided. */
    fun createScreen(
        uuid: UUID, ownerUuid: UUID, pos: Vector3i, facingUtil: FacingUtil,
        width: Int, height: Int, code: String, lang: String, isSync: Boolean,
    ) {
        val displayScreen = DisplayScreen(
            uuid, ownerUuid, pos.x(), pos.y(), pos.z(), facingUtil.toString(),
            width, height, isSync
        )

        val savedData = DisplaySettings.getDisplayData(uuid)
        displayScreen.renderDistance = savedData?.renderDistance ?: config.defaultDistance

        displayScreen.createTexture()
        DisplayManager.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)
    }

    /** Forwards a [Packets.Sync] packet to the matching display screen. */
    fun onSyncPacket(packet: Packets.Sync) {
        DisplayManager.screens[packet.uuid]?.updateData(packet)
    }

    /** Re-creates a display screen from previously saved [data] (used when the player re-enters render distance). */
    private fun restoreScreen(data: DisplaySettings.FullDisplayData) {
        val displayScreen = DisplayScreen(
            data.uuid, data.ownerUuid, data.x, data.y, data.z, data.facing,
            data.width, data.height, data.isSync
        )
        displayScreen.renderDistance = data.renderDistance
        displayScreen.savedTimeNanos = data.currentTimeNanos
        displayScreen.volume = data.volume
        displayScreen.quality = data.quality
        displayScreen.brightness = data.brightness
        displayScreen.muted = data.muted

        displayScreen.createTexture()
        DisplayManager.screens[displayScreen.uuid] = displayScreen

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

    /** Computes the shortest distance from [playerPos] to the nearest block in the screen's axis-aligned bounding box. */
    private fun distanceToScreen(
        x: Int, y: Int, z: Int, width: Int, height: Int, facing: String, playerPos: BlockPos
    ): Double {
        var maxX = x
        val maxY = y + height - 1
        var maxZ = z
        when (facing) {
            "NORTH", "SOUTH" -> maxX += width - 1
            "EAST", "WEST" -> maxZ += width - 1
        }
        return sqrt(playerPos.distSqr(BlockPos(
            minOf(maxOf(playerPos.x, x), maxX),
            minOf(maxOf(playerPos.y, y), maxY),
            minOf(maxOf(playerPos.z, z), maxZ)
        )))
    }

    /** Convenience wrapper over [distanceToScreen] that takes a [DisplaySettings.FullDisplayData] instead. */
    private fun distanceToData(data: DisplaySettings.FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos)

    /** Reads the mod version from resources and sends it to the server via [Packets.Version]. */
    private fun checkVersionAndSendPacket() {
        try {
            sendPacket(Packets.Version(GeneralUtil.getModVersion()))
        } catch (e: Exception) {
            logger.error("Unable to get version", e)
        }
    }

    /**
     * Main client tick handler. Detects level changes, manages render-distance unloading / restoring,
     * handles the right-click shortcut to open [DisplayMenu], and applies focus-mode blindness.
     */
    fun onEndTick(minecraft: Minecraft) {
        val level = minecraft.level
        if (level != null && minecraft.currentServer != null) {
            if (lastLevel == null) {
                lastLevel = level
                checkVersionAndSendPacket()
            }
            if (level !== lastLevel) {
                lastLevel = level
                DisplayManager.unloadAll()
                PipOverlayManager.clear()
                hoveredDisplayScreen = null
                checkVersionAndSendPacket()
            }
            wasInMultiplayer = true
        } else {
            if (wasInMultiplayer) {
                wasInMultiplayer = false
                DisplayManager.unloadAll()
                PipOverlayManager.clear()
                hoveredDisplayScreen = null
                lastLevel = null
                return
            }
        }

        val result = RayCastingUtil.rCBlock(64.0)
        hoveredDisplayScreen = null
        isOnScreen = false
        val player = minecraft.player ?: return

        unloadCheckTick++
        if (unloadCheckTick >= 10 && displaysEnabled && DisplayManager.unloadedScreens.isNotEmpty()) {
            unloadCheckTick = 0
            val playerPos = player.blockPosition()
            DisplayManager.unloadedScreens.values
                .filter { it.videoUrl.isNotEmpty() && distanceToData(it, playerPos) <= it.renderDistance }
                .toList()
                .forEach { data ->
                    DisplayManager.unloadedScreens.remove(data.uuid)
                    restoreScreen(data)
                }
        }

        for (displayScreen in DisplayManager.getScreens()) {
            val displayRenderDistance = displayScreen.renderDistance.toDouble()

            if ((displayRenderDistance < displayScreen.getDistanceToScreen(player.blockPosition()) || !displaysEnabled) && !displayScreen.isPopoutActive) {
                DisplayManager.saveScreenData(displayScreen)
                DisplayManager.unregisterScreen(displayScreen)
                if (hoveredDisplayScreen === displayScreen) {
                    hoveredDisplayScreen = null
                    isOnScreen = false
                }
            } else {
                if (result != null && displayScreen.isInScreen(result.blockPos)) {
                    hoveredDisplayScreen = displayScreen
                    isOnScreen = true
                }
                displayScreen.tick(player.blockPosition())
            }
        }

        val window = minecraft.window.handle()
        val pressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS

        if (pressed && !wasPressed) {
            if (player.isShiftKeyDown) checkAndOpenScreen()
        }
        wasPressed = pressed


        if (focusMode && hoveredDisplayScreen != null) {
            player.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 20 * 2, 1, false, false, false))
            wasFocused = true
        } else if (!focusMode && wasFocused) {
            player.removeEffect(MobEffects.BLINDNESS)
            wasFocused = false
        }
    }

    /** Opens the [DisplayMenu] for the currently hovered display screen, if any. */
    private fun checkAndOpenScreen() {
        hoveredDisplayScreen?.let { DisplayMenu.open(it) }
    }

    /** Renders all active PiP overlays on the HUD when the player is in-world and no screen is open. */
    fun onRenderHud(mc: Minecraft, graphics: GuiGraphicsExtractor, partialTick: Float) {
        if (mc.level == null || mc.player == null) return
        if (mc.screen != null) return
        PipOverlayManager.renderAll(mc, graphics, -1, -1, false, partialTick)
    }

    /** Delegates packet sending to the platform-specific [Mod] implementation. */
    fun sendPacket(packet: CustomPacketPayload) {
        mod.sendPacket(packet)
    }

    /** Unregisters and removes all data for the display identified by [Packets.Delete.uuid]. */
    fun onDeletePacket(packet: Packets.Delete) {
        DisplayManager.screens[packet.uuid]?.let { DisplayManager.unregisterScreen(it) }
        DisplayManager.unloadedScreens.remove(packet.uuid)
        DisplaySettings.removeDisplay(packet.uuid)
        logger.info("Display deleted and removed from saved data: ${packet.uuid}.")
    }

    /** Saves screen data to disk, stops all players, and interrupts background threads on mod shutdown. */
    fun onStop() {
        DisplayManager.saveAllScreens()
        timerThread.interrupt()
        DisplayManager.unloadAll()
        Focuser.instance.interrupt()
    }

    /** Updates the local premium flag from a server [Packets.Premium] packet. */
    fun onPremiumPacket(packet: Packets.Premium) {
        isPremium = packet.premium
    }

    /** Updates the local admin flag from a server [Packets.IsAdmin] packet. */
    fun onIsAdminPacket(packet: Packets.IsAdmin) {
        isAdmin = packet.isAdmin
    }

    /** Enables or disables the reporting feature based on a server [Packets.ReportEnabled] packet. */
    fun onReportEnabledPacket(packet: Packets.ReportEnabled) {
        isReportingEnabled = packet.enabled
    }

    /** Unregisters and clears saved data for the UUIDs listed in a [Packets.ClearCache] packet. */
    fun onClearCachePacket(packet: Packets.ClearCache) {
        packet.displayUuids.forEach { uuid ->
            DisplayManager.screens.remove(uuid)?.unregister()
            DisplaySettings.removeDisplay(uuid)
        }
    }
}
