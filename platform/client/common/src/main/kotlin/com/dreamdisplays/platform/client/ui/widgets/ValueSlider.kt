package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import net.minecraft.util.Mth

/**
 * Vanilla-styled horizontal slider over a fractional value in [0, 1]. The label and the apply action
 * are constructor lambdas, so call sites configure sliders declaratively instead of subclassing.
 *
 * @param initial starting fraction.
 * @param label formats the on-slider text for a given fraction.
 * @param live when true (default) [onApply] fires continuously as the user drags; when false it
 *   fires only once the drag/click is released. Use `live = false` for expensive actions (e.g. a
 *   quality switch that restarts the video) so a single drag across levels doesn't fire a burst.
 * @param step when set, clicks/drags snap the value to the nearest multiple of [step] instead of
 *   following the cursor continuously (e.g. `0.05` for 5% increments, or `1.0 / (positions - 1)`
 *   for a fixed number of evenly spaced stops). Settable from outside since some sliders (e.g.
 *   quality) have a stop count that only becomes known after construction.
 * @param onApply invoked when the user changes the value (after clamping).
 */
class ValueSlider(
    initial: Double,
    private val label: (Double) -> Component,
    private val live: Boolean = true,
    step: Double? = null,
    private val onApply: (Double) -> Unit,
) : UiWidget(Component.empty()) {

    var step: Double? = step

    /** True while a drag/click that changed the value is in progress but not yet committed (non-live). */
    private var pendingCommit: Boolean = false

    /** Current fraction in [0, 1]. Settable from outside (e.g. reset buttons); does not fire [onApply]. */
    var value: Double = initial
        set(v) {
            field = Mth.clamp(v, 0.0, 1.0)
        }

    private var sliderFocused: Boolean = false

    override fun handlesWholeWidgetCursor(): Boolean = false

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
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, handleSprite(),
            x + (value * (width - 8).toDouble()).toInt(), y, 8, height,
        )
        //?} else
        /*g.blitSprite(trackSprite(), x, y, width, height)
        g.blitSprite(handleSprite(), x + (value * (width - 8).toDouble()).toInt(), y, 8, height)*/
        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, label(value).copy().withStyle { it.withColor(color) }, 2)
        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active) CursorTypes.RESIZE_EW else CursorTypes.NOT_ALLOWED)
        }
        //?}
    }

    // NeoForge reroutes mouseClicked to a Neo-only 3-arg onClick that Fabric lacks, so the legacy
    // (1.21.1) branch overrides mouseClicked itself so the click-to-set fires on both platforms.
    // onDrag is unchanged across platforms.
    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        setValueFromMouse(event.x())
        playDownSound(Minecraft.getInstance().soundManager)
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        super.onDrag(event, dragX, dragY)
        setValueFromMouse(event.x())
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        setValueFromMouse(mouseX)
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }

    override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        super.onDrag(mouseX, mouseY, dragX, dragY)
        setValueFromMouse(mouseX)
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

    //? if >=1.21.11 {
    override fun onRelease(event: MouseButtonEvent) {
    //?} else
    /*override fun onRelease(mouseX: Double, mouseY: Double) {*/
        // Non-live sliders defer the (expensive) apply until the drag / click is released
        if (!live && pendingCommit) {
            pendingCommit = false
            onApply(value)
        }
    }

    /**
     * Converts [mouseX] to a fraction and updates [value]. For live sliders fires [onApply]
     * immediately on change; for non-live sliders marks the change for commit on release.
     */
    private fun setValueFromMouse(mouseX: Double) {
        val old = value
        var fraction = (mouseX - (x + 4).toDouble()) / (width - 8).toDouble()
        val snap = step
        if (snap != null && snap > 0.0) fraction = Math.round(fraction / snap) * snap
        value = fraction
        if (Mth.equal(old, value)) return
        if (live) onApply(value) else pendingCommit = true
    }

    companion object {
        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
