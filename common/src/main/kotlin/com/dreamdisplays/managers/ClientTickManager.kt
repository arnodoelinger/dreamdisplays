package com.dreamdisplays.managers

import com.dreamdisplays.Initializer
import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.client.ui.PipOverlayManager
import com.dreamdisplays.display.DisplayManager
import com.dreamdisplays.display.DisplayScreen
import com.dreamdisplays.net.Packets
import com.dreamdisplays.utils.GeneralUtil
import com.dreamdisplays.utils.RayCastingUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Handles per-tick client display state: level changes, hover, unloading, shortcuts, and focus mode.
 */
object ClientTickManager {
    private val logger = LoggerFactory.getLogger("DreamDisplays/ClientTickManager")

    private var wasPressed = false
    private var wasInMultiplayer = false
    @Volatile private var lastLevel: ClientLevel? = null
    private var wasFocused = false
    private var unloadCheckTick = 0
    private var hoveredDisplayScreen: DisplayScreen? = null

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level
        if (level != null && (minecraft.currentServer != null || minecraft.isLocalServer)) {
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

        // TODO: modify raycasting in future
        val result = RayCastingUtil.rCBlock(64.0)
        hoveredDisplayScreen = null
        ClientStateManager.isOnScreen = false
        val player = minecraft.player ?: return
        val playerPos = player.blockPosition()

        unloadCheckTick++
        if (unloadCheckTick >= 10 && ClientStateManager.displaysEnabled && DisplayManager.unloadedScreens.isNotEmpty()) {
            unloadCheckTick = 0
            DisplayLifecycleManager.restoreVisibleUnloadedScreens(playerPos)
        }

        for (displayScreen in DisplayManager.getScreens()) {
            val outOfRange = displayScreen.renderDistance < displayScreen.getDistanceToScreen(playerPos)
            if ((outOfRange || !ClientStateManager.displaysEnabled) && !displayScreen.isPopoutActive) {
                DisplayManager.saveScreenData(displayScreen)
                DisplayManager.unregisterScreen(displayScreen)
                if (hoveredDisplayScreen === displayScreen) {
                    hoveredDisplayScreen = null
                    ClientStateManager.isOnScreen = false
                }
            } else {
                if (result != null && displayScreen.isInScreen(result.blockPos)) {
                    hoveredDisplayScreen = displayScreen
                    ClientStateManager.isOnScreen = true
                }
                displayScreen.tick(playerPos)
            }
        }

        val window = minecraft.window.handle()
        val pressed = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
        if (pressed && !wasPressed && player.isShiftKeyDown) {
            hoveredDisplayScreen?.let { DisplayMenu.open(it) }
        }
        wasPressed = pressed

        // TODO: implement focus mode in future
        if (ClientStateManager.focusMode && hoveredDisplayScreen != null) {
            player.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 20 * 2, 1, false, false, false))
            wasFocused = true
        } else if (!ClientStateManager.focusMode && wasFocused) {
            player.removeEffect(MobEffects.BLINDNESS)
            wasFocused = false
        }
    }

    private fun checkVersionAndSendPacket() {
        try {
            Initializer.sendPacket(Packets.Version(GeneralUtil.getModVersion()))
        } catch (e: Exception) {
            logger.error("Unable to get version", e)
        }
    }
}
