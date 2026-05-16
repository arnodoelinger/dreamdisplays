package com.dreamdisplays

import com.dreamdisplays.client.ui.DisplayMenu
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
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
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

    private var wasPressed = false
    private var wasInMultiplayer = false
    @Volatile private var lastLevel: ClientLevel? = null
    private var wasFocused = false

    var config: Config = Config(File("./config/$MOD_ID"))

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
    var isReportingEnabled: Boolean = true

    private var unloadCheckTick: Int = 0
    private var hoveredDisplayScreen: DisplayScreen? = null
    private lateinit var mod: Mod

    fun onModInit(dreamDisplaysMod: Mod) {
        mod = dreamDisplaysMod
        LoggingManager.setLogger(LoggerFactory.getLogger(MOD_ID))
        LoggingManager.info("[Initializer] Starting Dream Displays...")
        config.reload()

        DisplaySettings.load()

        YtDlp.prewarmAsync()
        FFmpegBinary.prewarmAsync()
        Thread({ FormatDiskCache.sweepExpired() }, "dreamdisplays-cache-sweep").start()
        Focuser().start()

        timerThread.start()
    }

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

    fun onDisplayEnabledPacket(packet: Packets.DisplayEnabled) {
        displaysEnabled = packet.enabled
        config.displaysEnabled = packet.enabled
        config.save()
    }

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

        DisplayManager.registerScreen(displayScreen)
        if (code != "") displayScreen.loadVideo(code, lang)
    }

    fun onSyncPacket(packet: Packets.Sync) {
        DisplayManager.screens[packet.uuid]?.updateData(packet)
    }

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

        DisplayManager.screens[displayScreen.uuid] = displayScreen

        if (data.videoUrl.isNotEmpty()) {
            displayScreen.loadVideo(data.videoUrl, data.lang)
        }
    }

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

    private fun distanceToData(data: DisplaySettings.FullDisplayData, playerPos: BlockPos) =
        distanceToScreen(data.x, data.y, data.z, data.width, data.height, data.facing, playerPos)

    private fun checkVersionAndSendPacket() {
        try {
            sendPacket(Packets.Version(GeneralUtil.getModVersion()))
        } catch (e: Exception) {
            LoggingManager.error("[Initializer] Unable to get version", e)
        }
    }

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
                hoveredDisplayScreen = null
                checkVersionAndSendPacket()
            }
            wasInMultiplayer = true
        } else {
            if (wasInMultiplayer) {
                wasInMultiplayer = false
                DisplayManager.unloadAll()
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

            if (displayRenderDistance < displayScreen.getDistanceToScreen(player.blockPosition()) || !displaysEnabled) {
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

    private fun checkAndOpenScreen() {
        hoveredDisplayScreen?.let { DisplayMenu.open(it) }
    }

    fun sendPacket(packet: CustomPacketPayload) {
        mod.sendPacket(packet)
    }

    fun onDeletePacket(packet: Packets.Delete) {
        DisplayManager.screens[packet.uuid]?.let { DisplayManager.unregisterScreen(it) }
        DisplayManager.unloadedScreens.remove(packet.uuid)
        DisplaySettings.removeDisplay(packet.uuid)
        LoggingManager.info("[Initializer] Display deleted and removed from saved data: ${packet.uuid}")
    }

    fun onStop() {
        DisplayManager.saveAllScreens()
        timerThread.interrupt()
        DisplayManager.unloadAll()
        Focuser.instance.interrupt()
    }

    fun onPremiumPacket(packet: Packets.Premium) {
        isPremium = packet.premium
    }

    fun onReportEnabledPacket(packet: Packets.ReportEnabled) {
        isReportingEnabled = packet.enabled
    }

    fun onClearCachePacket(packet: Packets.ClearCache) {
        packet.displayUuids.forEach { uuid ->
            DisplayManager.screens.remove(uuid)?.unregister()
            DisplaySettings.removeDisplay(uuid)
        }
    }
}
