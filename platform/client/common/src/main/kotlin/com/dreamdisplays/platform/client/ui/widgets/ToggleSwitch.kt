package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

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
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        val handleX = if (value) x + width - 8 else x
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)
        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, label(value).copy().withStyle { it.withColor(color) }, 2)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        value = !value
        onApply(value)
        playDownSound(Minecraft.getInstance().soundManager)
    }

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
