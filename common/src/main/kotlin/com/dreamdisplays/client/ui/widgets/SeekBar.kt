package com.dreamdisplays.client.ui.widgets

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.kit.UiText
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
 * Playback progress bar with drag-to-seek. Position and duration are pulled from lambdas every
 * frame; the seek is only committed when the drag ends (via [commitDragIfActive] from the screen's
 * `mouseReleased`), so scrubbing doesn't spam seeks.
 *
 * @param current supplies the current playback position in nanoseconds.
 * @param duration supplies the total duration in nanoseconds (`<= 0` while unknown).
 * @param onSeek invoked with the target position in nanoseconds when a drag is committed.
 *
 * @since 1.8.0
 */
class SeekBar(
    private val current: () -> Long,
    private val duration: () -> Long,
    private val onSeek: (Long) -> Unit,
) : UiWidget(Component.empty()) {

    private var sliderFocused = false
    private var dragging = false
    private var dragTargetNanos = 0L

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val dur = duration()
        val cur = if (dragging) dragTargetNanos else current()
        val value = if (dur > 0) Mth.clamp(cur / dur.toDouble(), 0.0, 1.0) else 0.0

        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        val handleX = x + (value * (width - 8).toDouble()).toInt()
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)

        drawScrollingLabel(g, timeLabel(cur, dur), 4)
    }

    /** Formats the current / total time as a colored text component for display on the bar. */
    private fun timeLabel(cur: Long, dur: Long): MutableComponent {
        val color = if (active) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt()
        return Component.literal("${UiText.formatTime(cur)} / ${UiText.formatTime(dur)}")
            .copy().withStyle { it.withColor(color) }
    }

    /** Returns the track sprite, highlighted when the widget has keyboard focus. */
    private fun trackSprite(): Identifier =
        if (isFocused && !sliderFocused) TRACK_HIGHLIGHTED else TRACK

    /** Returns the handle sprite, highlighted when hovered or dragging. */
    private fun handleSprite(): Identifier =
        if (!isHovered && !sliderFocused) HANDLE else HANDLE_HIGHLIGHTED

    override fun createNarrationMessage(): MutableComponent =
        Component.translatable("gui.narrate.slider", timeLabel(current(), duration()))

    override fun updateWidgetNarration(builder: NarrationElementOutput) {
        builder.add(NarratedElementType.TITLE, createNarrationMessage())
    }

    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        if (!active) return
        val dur = duration()
        if (dur <= 0) return
        dragTargetNanos = positionFromMouse(event.x(), dur)
        dragging = true
    }

    override fun onDrag(event: MouseButtonEvent, dragX: Double, dragY: Double) {
        super.onDrag(event, dragX, dragY)
        if (!dragging || !active) return
        val dur = duration()
        if (dur <= 0) return
        dragTargetNanos = positionFromMouse(event.x(), dur)
    }

    /** Commits an in-flight drag as a seek; returns true if a drag was active. Call from `mouseReleased`. */
    fun commitDragIfActive(): Boolean {
        if (!dragging) return false
        dragging = false
        onSeek(dragTargetNanos)
        return true
    }

    /** Converts a mouse X coordinate to a playback position in nanoseconds within [dur]. */
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
        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
