package com.dreamdisplays.client.input

import com.dreamdisplays.client.core.DreamServices
import com.dreamdisplays.client.core.getOrNull
import com.dreamdisplays.client.ui.DisplayMenu
import com.dreamdisplays.displays.DisplayRegistry
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Opens the display menu on sneak + menu-key click while the crosshair targets a display.
 * Consumes the click and emits [DisplayInteraction.RightClicked] for other subscribers.
 *
 * @since 1.0.0
 */
class DisplayMenuInputHandler : InputHandler {
    /** Consumes a [InputAction.MouseClicked] matching the menu binding when sneaking at a display. */
    override fun handle(action: InputAction): Boolean {
        if (action !is InputAction.MouseClicked) return false
        val binding = DreamServices.registry.getOrNull<KeyBindingRegistry>()
            ?.findById(OPEN_MENU_BINDING_ID) ?: OPEN_MENU_BINDING
        if (action.button != binding.defaultKey) return false

        val player = Minecraft.getInstance().player ?: return false
        if (!player.isShiftKeyDown) return false

        val target = DreamServices.registry.getOrNull<DisplayInteractionService>()
            ?.getCurrentTarget() ?: return false
        val screen = DisplayRegistry.screens[target.displayId.uuid] ?: return false

        DreamServices.registry.getOrNull<DisplayInteractionService>()
            ?.emit(DisplayInteraction.RightClicked(target.displayId))
        DisplayMenu.open(screen)
        return true
    }

    companion object {
        /** Id of the menu-open binding in the [KeyBindingRegistry]. */
        const val OPEN_MENU_BINDING_ID = "dreamdisplays.open_menu"

        /** Default menu-open binding: right mouse button (with sneak held). */
        val OPEN_MENU_BINDING = KeyBinding(
            id = OPEN_MENU_BINDING_ID,
            defaultKey = GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            category = "dreamdisplays",
            description = "Open the display menu (sneak + click on a display)",
        )
    }
}
