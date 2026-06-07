package com.dreamdisplays.client.ui.widgets

import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

/** Slider widget. **/
// TODO: rewrite this class entirely in 1.9.0
abstract class SliderWidget(
    x: Int, y: Int, width: Int, height: Int,
    message: Component,
    var value: Double,
) : AbstractWidget(x, y, width, height, message) {

    private var sliderFocused: Boolean = false

    /** Returns the track sprite, highlighted when the widget has keyboard focus. */
    private fun getTexture(): Identifier =
        if (isFocused && !sliderFocused) HIGHLIGHTED_TEXTURE_ID else TEXTURE_ID

    /** Returns the handle sprite, highlighted when hovered or slider-focused. */
    private fun getHandleTexture(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE_TEXTURE_ID else HANDLE_HIGHLIGHTED_TEXTURE_ID

    override fun createNarrationMessage(): MutableComponent =
        Component.translatable("gui.narrate.slider", message)

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        builder.add(NarratedElementType.TITLE, createNarrationMessage())
        if (active) {
            if (isFocused) builder.add(
                NarratedElementType.USAGE,
                Component.translatable("narration.slider.usage.focused")
            )
            else builder.add(
                NarratedElementType.USAGE,
                Component.translatable("narration.slider.usage.hovered")
            )
        }
    }

    //? if >=26 {
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, getTexture(), x, y, width, height)
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, getHandleTexture(),
            x + (value * (width - 8).toDouble()).toInt(), y, 8, height
        )
        val i = if (active) 16777215 else 10526880
        val msg = message.copy().withStyle { it.withColor(i) }
        extractScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            msg, 2
        )
    }
    //?} else
    /*override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, getTexture(), x, y, width, height)
        g.blitSprite(
            RenderPipelines.GUI_TEXTURED, getHandleTexture(),
            x + (value * (width - 8).toDouble()).toInt(), y, 8, height
        )
        val i = if (active) 16777215 else 10526880
        val msg = message.copy().withStyle { it.withColor(i) }
        renderScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            msg, 2
        )
    }*/

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        setValueFromMouse(event.x())
        super.playDownSound(Minecraft.getInstance().soundManager)
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

    /** Converts [mouseX] to a fractional slider position and applies it. */
    private fun setValueFromMouse(mouseX: Double) {
        setFractionalValue((mouseX - (x + 4).toDouble()) / (width - 8).toDouble())
    }

    /** Clamps [fractional] to [0, 1], updates [value], and calls [applyValue] if the value changed. */
    private fun setFractionalValue(fractional: Double) {
        val old = value
        value = Mth.clamp(fractional, 0.0, 1.0)
        if (!Mth.equal(old, value)) applyValue()
        updateMessage()
    }

    abstract fun updateMessage()
    protected abstract fun applyValue()

    companion object {
        private val TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider")
        private val HIGHLIGHTED_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
