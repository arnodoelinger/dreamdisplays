package com.dreamdisplays.platform.client.input

import com.dreamdisplays.api.runtime.registry.service.getOrNull
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.ui.DisplayMenu
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
//? if >=26.3 {
import com.mojang.blaze3d.platform.InputConstants
//?} else
/*import org.lwjgl.glfw.GLFW*/

/**
 * Opens the display menu on sneak + menu-key click while the crosshair targets a display.
 * Consumes the click and emits [DisplayInteraction.RightClicked] for other subscribers.
 */
class DisplayMenuInputHandler : InputHandler {
    /** Consumes a [InputAction.MouseClicked] matching the menu binding when sneaking at a display. */
    override fun handle(action: InputAction): Boolean {
        if (action !is InputAction.MouseClicked) return false
        val binding = DreamServices.registry.getOrNull<KeyBindingRegistry>()
            ?.findById(OPEN_MENU_BINDING_ID) ?: OPEN_MENU_BINDING
        if (action.button != binding.defaultKey) return false
        return tryOpenFromWorld()
    }

    companion object {
        /** ID of the menu-open binding in the [KeyBindingRegistry]. */
        const val OPEN_MENU_BINDING_ID = "dreamdisplays.open_menu"

        /** Default menu-open binding: right mouse button (with sneak held). */
        val OPEN_MENU_BINDING = KeyBinding(
            id = OPEN_MENU_BINDING_ID,
            //? if >=26.3 {
            defaultKey = InputConstants.MOUSE_BUTTON_RIGHT,
            //?} else
            /*defaultKey = GLFW.GLFW_MOUSE_BUTTON_RIGHT,*/
            category = "dreamdisplays",
            description = "Open the display menu (sneak + click on a display)",
        )

        /**
         * Opens the display menu when sneaking and looking at a display. Used from the tick poll and
         * from the client use-block callback.
         */
        fun tryOpenFromWorld(): Boolean {
            val mc = Minecraft.getInstance()
            if (MinecraftScreenUtil.currentScreen(mc) != null) return false
            val player = mc.player ?: return false
            if (!player.isShiftKeyDown) return false
            val target = DreamServices.registry.getOrNull<DisplayInteractionService>()
                ?.getCurrentTarget() ?: return false
            val screen = DisplayRegistry.screens[target.displayId.uuid] ?: return false
            DreamServices.registry.getOrNull<DisplayInteractionService>()
                ?.emit(DisplayInteraction.RightClicked(target.displayId))
            DisplayMenu.open(screen)
            return true
        }
    }
}
