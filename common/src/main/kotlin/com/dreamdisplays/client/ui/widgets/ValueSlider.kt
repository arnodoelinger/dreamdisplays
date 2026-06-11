package com.dreamdisplays.client.ui.widgets

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.kit.UiWidget
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/**
 * Vanilla-styled horizontal slider over a fractional value in [0, 1]. The label and the apply action
 * are constructor lambdas, so call sites configure sliders declaratively instead of subclassing.
 *
 * @param initial starting fraction.
 * @param label formats the on-slider text for a given fraction.
 * @param onApply invoked when the user changes the value (after clamping).
 *
 * @since 1.0.0
 */
class ValueSlider(
    initial: Double,
    private val label: (Double) -> Component,
    private val onApply: (Double) -> Unit,
) : UiWidget(Component.empty()) {

    /** Current fraction in [0, 1]. Settable from outside (e.g. reset buttons); does not fire [onApply]. */
    var value: Double = initial
        set(v) {
            field = Mth.clamp(v, 0.0, 1.0)
        }

    private var sliderFocused: Boolean = false

    /** Returns the track sprite, highlighted when the widget has keyboard focus. */
    private fun trackSprite(): Identifier =
        if (isFocused && !sliderFocused) TRACK_HIGHLIGHTED else TRACK

    /** Returns the handle sprite, highlighted when hovered or slider-focused. */
    private fun handleSprite(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE else HANDLE_HIGHLIGHTED

    override fun createNarrationMessage(): MutableComponent =
        Component.translatable("gui.narrate.slider", label(value))

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        builder.add(NarratedElementType.TITLE, createNarrationMessage())
        if (active) {
            builder.add(
                NarratedElementType.USAGE,
                Component.translatable(if (isFocused) "narration.slider.usage.focused" else "narration.slider.usage.hovered"),
            )
        }
    }

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, handleSprite(),
            x + (value * (width - 8).toDouble()).toInt(), y, 8, height,
        )
        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, label(value).copy().withStyle { it.withColor(color) }, 2)
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        setValueFromMouse(event.x())
        playDownSound(Minecraft.getInstance().soundManager)
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        super.onDrag(event, dragX, dragY)
        setValueFromMouse(event.x())
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

    /** Converts [mouseX] to a fraction, applies it, and fires [onApply] if it changed. */
    private fun setValueFromMouse(mouseX: Double) {
        val old = value
        value = (mouseX - (x + 4).toDouble()) / (width - 8).toDouble()
        if (!Mth.equal(old, value)) onApply(value)
    }

    companion object {
        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
