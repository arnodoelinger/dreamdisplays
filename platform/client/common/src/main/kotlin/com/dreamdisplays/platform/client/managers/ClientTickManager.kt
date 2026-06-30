package com.dreamdisplays.platform.client.managers

import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.core.ClientApplication
import com.dreamdisplays.platform.client.core.ClientLifecycleEvent
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.api.runtime.getOrNull
import com.dreamdisplays.platform.client.input.DisplayInteraction
import com.dreamdisplays.platform.client.input.DisplayInteractionService
import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import com.dreamdisplays.platform.client.input.InputAction
import com.dreamdisplays.platform.client.input.InputHandler
import com.dreamdisplays.platform.client.input.KeyBindingRegistry
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
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
    /** Edge-detect state for the menu-open button. */
    private var wasPressed = false

    /** True while the player is connected to a server / local world. */
    private var wasInMultiplayer = false

    /** The level seen last tick, used to detect level changes. */
    @Volatile
    private var lastLevel: ClientLevel? = null

    /** Whether focus-mode blindness was applied last tick. */
    private var wasFocused = false

    /** Counter that throttles the unloaded-screen restore check. */
    private var unloadCheckTick = 0

    /** The display currently under the crosshair, or `null`. */
    private var hoveredDisplayScreen: DisplayScreen? = null

    /** Id of the display hovered last tick, used to emit look events on change. */
    private var lastHoveredId: UUID? = null

    /** Monotonic tick counter emitted with [ClientLifecycleEvent.Tick]. */
    private var tickCount = 0L

    /** Main per-tick update: level changes, hover, render-distance (un)loading, the menu shortcut, and focus mode. */
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
            val shouldUnload = (outOfRange || !ClientStateManager.displaysEnabled) && !displayScreen.isPopoutActive

            // Already parked warm: wake it when back in range, or tear it down once it has been dormant
            // past the pool TTL (freeing its decoder + texture; the snapshot cache then bridges a return).
            if (displayScreen.isDormant) {
                when {
                    !shouldUnload -> displayScreen.wake()
                    displayScreen.dormantExpired(WarmParkPolicy.ttlNanos) -> compressDormant(displayScreen)
                    displayScreen.dormantExpired(WarmParkPolicy.demoteAfterNanos) -> compressDormant(displayScreen)
                }
                continue
            }

            if (shouldUnload) {
                // Keep a bounded pool of Local VOD displays warm (decoder + audio open, frozen), so walking
                // back is instant. Older / out-of-budget warm parks are compressed into replay snapshots.
                // Only the natural "left render distance" case parks; disabling displays tears down fully.
                val warmEligible = outOfRange && ClientStateManager.displaysEnabled
                if (warmEligible && displayScreen.canWarmPark() && reserveWarmSlot(displayScreen)) {
                    displayScreen.goDormant()
                } else {
                    DisplayRegistry.saveScreenData(displayScreen)
                    DisplayRegistry.unregisterScreen(displayScreen)
                }
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
        // through the InputHandler chain (DisplayMenuInputHandler consumes sneak + click-on-display).
        val window =
            //? if >=1.21.11 {
            minecraft.window.handle()
            //?} else
            /*minecraft.window.window*/
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

    /** Frees a fully warm dormant display, keeping only its cheap replay snapshot for fast reappearance. */
    private fun compressDormant(displayScreen: DisplayScreen) {
        DisplayRegistry.saveScreenData(displayScreen)
        DisplayRegistry.unregisterScreen(displayScreen)
    }

    /** Ensures there is budget for [candidate], evicting oldest parked displays into snapshots when needed. */
    private fun reserveWarmSlot(candidate: DisplayScreen): Boolean {
        if (WarmParkPolicy.maxFullWarmDisplays <= 0) return false
        repeat(WarmParkPolicy.maxFullWarmDisplays + 1) {
            val dormant = DisplayRegistry.dormantScreens()
            if (WarmParkPolicy.fits(dormant, candidate)) return true
            val victim = dormant.minByOrNull { it.dormantSinceNanos() } ?: return false
            compressDormant(victim)
        }
        return false
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
