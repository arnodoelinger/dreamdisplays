package com.dreamdisplays.managers

import com.dreamdisplays.api.DisplayId
import com.dreamdisplays.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.client.core.ClientApplication
import com.dreamdisplays.client.core.ClientLifecycleEvent
import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.input.DisplayInteraction
import com.dreamdisplays.client.input.DisplayInteractionService
import com.dreamdisplays.client.input.DisplayMenuInputHandler
import com.dreamdisplays.client.input.InputAction
import com.dreamdisplays.client.input.InputHandler
import com.dreamdisplays.client.input.KeyBindingRegistry
import com.dreamdisplays.client.overlay.OverlayManager
import com.dreamdisplays.displays.DisplayRegistry
import com.dreamdisplays.displays.DisplayScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import org.lwjgl.glfw.GLFW
import java.util.UUID

/**
 * Handles per-tick client display state: level changes, hover, unloading, shortcuts, and focus mode.
 */
object ClientTickManager {
    private var wasPressed = false
    private var wasInMultiplayer = false
    @Volatile private var lastLevel: ClientLevel? = null
    private var wasFocused = false
    private var unloadCheckTick = 0
    private var hoveredDisplayScreen: DisplayScreen? = null
    private var lastHoveredId: UUID? = null
    private var tickCount = 0L

    fun tick(minecraft: Minecraft) {
        tickCount++
        DreamServices.registry.getOrNull<ClientApplication>()
            ?.emit(ClientLifecycleEvent.Tick(tickCount))

        val level = minecraft.level
        if (level != null && (minecraft.currentServer != null || minecraft.isLocalServer)) {
            if (lastLevel == null) {
                lastLevel = level
                checkVersionAndSendPacket()
            }
            if (level !== lastLevel) {
                lastLevel = level
                DisplayRegistry.unloadAll()
                DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
                hoveredDisplayScreen = null
                checkVersionAndSendPacket()
            }
            wasInMultiplayer = true
        } else {
            if (wasInMultiplayer) {
                wasInMultiplayer = false
                DisplayRegistry.unloadAll()
                DreamServices.registry.getOrNull<OverlayManager>()?.closeAll()
                hoveredDisplayScreen = null
                lastLevel = null
                return
            }
        }

        // Display under the crosshair, resolved through the DisplayInteractionService contract
        // (replaces the inline RayCastingUtil + isInScreen mapping this manager used to duplicate).
        val hoveredId = DreamServices.registry.getOrNull<DisplayInteractionService>()
            ?.getCurrentTarget()?.displayId?.uuid
        notifyHoverChange(hoveredId)
        hoveredDisplayScreen = null
        ClientStateManager.isOnScreen = false
        val player = minecraft.player ?: return
        val playerPos = player.blockPosition()

        unloadCheckTick++
        if (unloadCheckTick >= 10 && ClientStateManager.displaysEnabled && DisplayRegistry.unloadedScreens.isNotEmpty()) {
            unloadCheckTick = 0
            DisplayLifecycleManager.restoreVisibleUnloadedScreens(playerPos)
        }

        for (displayScreen in DisplayRegistry.getScreens()) {
            val outOfRange = displayScreen.renderDistance < displayScreen.getDistanceToScreen(playerPos)
            if ((outOfRange || !ClientStateManager.displaysEnabled) && !displayScreen.isPopoutActive) {
                DisplayRegistry.saveScreenData(displayScreen)
                DisplayRegistry.unregisterScreen(displayScreen)
                if (hoveredDisplayScreen === displayScreen) {
                    hoveredDisplayScreen = null
                    ClientStateManager.isOnScreen = false
                }
            } else {
                if (displayScreen.uuid == hoveredId) {
                    hoveredDisplayScreen = displayScreen
                    ClientStateManager.isOnScreen = true
                }
                displayScreen.tick(playerPos)
            }
        }

        // The menu-open button comes from the KeyBindingRegistry; the click itself is routed
        // through the InputHandler chain (DisplayMenuInputHandler consumes sneak+click-on-display).
        val window = minecraft.window.handle()
        val menuButton = DreamServices.registry.getOrNull<KeyBindingRegistry>()
            ?.findById(DisplayMenuInputHandler.OPEN_MENU_BINDING_ID)?.defaultKey
            ?: GLFW.GLFW_MOUSE_BUTTON_RIGHT
        val pressed = GLFW.glfwGetMouseButton(window, menuButton) == GLFW.GLFW_PRESS
        if (pressed && !wasPressed) {
            DreamServices.registry.getOrNull<InputHandler>()?.handle(
                InputAction.MouseClicked(minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos(), menuButton)
            )
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

    /** Emits [DisplayInteraction.Looked] / [DisplayInteraction.LookedAway] when the crosshair target changes. */
    private fun notifyHoverChange(hoveredId: UUID?) {
        if (hoveredId == lastHoveredId) return
        val service = DreamServices.registry.getOrNull<DisplayInteractionService>()
        lastHoveredId?.let { service?.emit(DisplayInteraction.LookedAway(DisplayId(it))) }
        hoveredId?.let { service?.emit(DisplayInteraction.Looked(DisplayId(it))) }
        lastHoveredId = hoveredId
    }

    /** Kicks off the capability handshake (legacy Version packet) for the just-joined server. */
    private fun checkVersionAndSendPacket() {
        DreamServices.registry.getOrNull<CapabilityNegotiationService>()?.advertise()
    }
}
