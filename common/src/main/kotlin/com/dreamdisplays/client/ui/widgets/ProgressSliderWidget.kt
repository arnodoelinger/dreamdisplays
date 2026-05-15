package com.dreamdisplays.client.ui.widgets

import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import java.util.function.LongConsumer
import java.util.function.LongSupplier

/** Progress slider widget **/
class ProgressSliderWidget(
    x: Int, y: Int, width: Int, height: Int,
    private val currentSupplier: LongSupplier,
    private val durationSupplier: LongSupplier,
    private val seekConsumer: LongConsumer,
) : AbstractWidget(x, y, width, height, Component.empty()) {

    private var sliderFocused = false
    private var dragging = false
    private var dragTargetNanos = 0L

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val dur = durationSupplier.asLong
        val cur = if (dragging) dragTargetNanos else currentSupplier.asLong
        val value = if (dur > 0) Mth.clamp(cur / dur.toDouble(), 0.0, 1.0) else 0.0

        g.blitSprite(RenderPipelines.GUI_TEXTURED, getTrackSprite(), x, y, width, height)
        val handleX = x + (value * (width - 8).toDouble()).toInt()
        g.blitSprite(RenderPipelines.GUI_TEXTURED, getHandleSprite(), handleX, y, 8, height)

        val label = buildLabel(cur, dur)
        renderScrollingStringOverContents(
            g.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.TOOLTIP_AND_CURSOR),
            label, 4)
    }

    private fun buildLabel(cur: Long, dur: Long): MutableComponent {
        val color = if (active) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt()
        return Component.literal("${formatTime(cur)} / ${formatTime(dur)}")
            .copy().withStyle { it.withColor(color) }
    }

    private fun getTrackSprite(): Identifier =
        if (isFocused && !sliderFocused) HIGHLIGHTED_TEXTURE_ID else TEXTURE_ID

    private fun getHandleSprite(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE_TEXTURE_ID else HANDLE_HIGHLIGHTED_TEXTURE_ID

    override fun createNarrationMessage(): MutableComponent =
        Component.translatable("gui.narrate.slider",
            buildLabel(currentSupplier.asLong, durationSupplier.asLong))

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        builder.add(NarratedElementType.TITLE, createNarrationMessage())
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        if (!active) return
        val dur = durationSupplier.asLong
        if (dur <= 0) return
        dragTargetNanos = positionFromMouse(event.x(), dur)
        dragging = true
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        super.onDrag(event, dragX, dragY)
        if (!dragging || !active) return
        val dur = durationSupplier.asLong
        if (dur <= 0) return
        dragTargetNanos = positionFromMouse(event.x(), dur)
    }

    fun commitDragIfActive(): Boolean {
        if (!dragging) return false
        dragging = false
        seekConsumer.accept(dragTargetNanos)
        return true
    }

    private fun positionFromMouse(mouseX: Double, dur: Long): Long {
        val pct = Mth.clamp((mouseX - (x + 4).toDouble()) / (width - 8).toDouble(), 0.0, 1.0)
        return (pct * dur).toLong()
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
        private val TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider")
        private val HIGHLIGHTED_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED_TEXTURE_ID = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")

        private fun formatTime(nanos: Long): String {
            if (nanos <= 0) return "00:00"
            val s = nanos / 1_000_000_000L
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
            else String.format("%02d:%02d", m, sec)
        }
    }
}
