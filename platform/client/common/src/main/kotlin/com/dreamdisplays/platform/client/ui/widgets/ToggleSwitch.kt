package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Slider-styled boolean toggle: the handle sits at the left or right end of the track. Label and
 * apply action are constructor lambdas, matching [ValueSlider]'s declarative style.
 *
 * @param initial starting state.
 * @param label formats the on-toggle text for a given state.
 * @param onApply invoked when the user (or [set]) changes the state.
 */
class ToggleSwitch(
    initial: Boolean,
    private val label: (Boolean) -> Component,
    private val onApply: (Boolean) -> Unit,
) : UiWidget(Component.empty()) {

    /** Current toggle state. Use [set] to change it programmatically with the apply action. */
    var value: Boolean = initial
        private set

    private var sliderFocused: Boolean = false

    override fun handlesWholeWidgetCursor(): Boolean = false

    /** Sets the state to [newValue]; fires [onApply] only when the state actually changes. */
    fun set(newValue: Boolean) {
        if (value != newValue) {
            value = newValue
            onApply(newValue)
        }
    }

    /** Returns the track sprite, highlighted when the widget has keyboard focus. */
    private fun trackSprite(): Identifier =
        if (isFocused && !sliderFocused) TRACK_HIGHLIGHTED else TRACK

    /** Returns the handle sprite, highlighted when hovered or slider-focused. */
    private fun handleSprite(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE else HANDLE_HIGHLIGHTED

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        //?} else
        /*g.blitSprite(trackSprite(), x, y, width, height)*/
        val handleX = if (value) x + width - 8 else x
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)
        //?} else
        /*g.blitSprite(handleSprite(), handleX, y, 8, height)*/
        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, label(value).copy().withStyle { it.withColor(color) }, 2)
        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
        }
        //?}
    }

    // NeoForge reroutes mouseClicked to a Neo-only 3-arg onClick that Fabric lacks, so the legacy
    // (1.21.1) branch overrides mouseClicked itself so the toggle fires on both platforms.
    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        value = !value
        onApply(value)
        playDownSound(Minecraft.getInstance().soundManager)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        value = !value
        onApply(value)
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }*/

    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)
        if (!focused) {
            sliderFocused = false
        } else {
            val t = Minecraft.getInstance().lastInputType
            if (t == InputType.MOUSE || t == InputType.KEYBOARD_TAB) sliderFocused = true
        }
    }

    companion object {
        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
