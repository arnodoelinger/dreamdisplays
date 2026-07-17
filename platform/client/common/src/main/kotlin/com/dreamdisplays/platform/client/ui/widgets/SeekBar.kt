package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.render.ScrubPreview
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=26 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.InputType
import net.minecraft.client.Minecraft
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}
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
//? if >=1.21.11 {
import net.minecraft.util.ARGB
//?}
import net.minecraft.util.Mth

/**
 * Playback progress bar with drag-to-seek. Position and duration are pulled from lambdas every
 * frame; the seek is only committed when the drag ends (via [commitDragIfActive] from the screen's
 * `mouseReleased`), so scrubbing doesn't spam seeks.
 *
 * @param current supplies the current playback position in nanoseconds.
 * @param duration supplies the total duration in nanoseconds (`<= 0` while unknown).
 * @param onSeek invoked with the target position in nanoseconds when a drag is committed.
 * @param previewFrame optionally supplies a scrub-preview texture for a hovered position in
 * nanoseconds; returning null (or leaving this unset) shows no preview.
 * @param waitingLabel optionally supplies a status string (e.g. "Waiting for video...") to show in
 * place of the time label while non-null; drawn in a dim grey.
 */
class SeekBar(
    private val current: () -> Long,
    private val duration: () -> Long,
    private val previewFrame: ((Long) -> Identifier?)? = null,
    private val waitingLabel: (() -> String?)? = null,
    private val onSeek: (Long) -> Unit,
) : UiWidget(Component.empty()) {

    private var sliderFocused = false
    private var dragging = false
    private var dragTargetNanos = 0L
    private var hoverFade = 0f
    private var previewFade = 0f

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val dur = duration()
        val cur = if (dragging) dragTargetNanos else current()
        val value = if (dur > 0) Mth.clamp(cur / dur.toDouble(), 0.0, 1.0) else 0.0

        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        //?} else
        /*g.blitSprite(trackSprite(), x, y, width, height)*/
        val handleX = x + (value * (width - 8).toDouble()).toInt()
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)
        //?} else
        /*g.blitSprite(handleSprite(), handleX, y, 8, height)*/

        val hoverTarget = if (active && !dragging && dur > 0 && isHovered) 1f else 0f
        hoverFade += (hoverTarget - hoverFade) * FADE_SPEED
        if (hoverFade > 0.01f) {
            val hoverPct = Mth.clamp((mouseX - (x + 4).toDouble()) / (width - 8).toDouble(), 0.0, 1.0)
            val hoverX = x + (hoverPct * (width - 8)).toInt()
            //? if >=1.21.11 {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, HANDLE, hoverX, y, 8, height, ARGB.white(GHOST_HANDLE_ALPHA * hoverFade))
            //?} else
            /*g.blitSprite(HANDLE, hoverX, y, 8, height)*/
        }

        val waiting = waitingLabel?.invoke()
        if (waiting != null) {
            drawScrollingLabel(g, Component.literal(waiting).copy().withStyle { it.withColor(WAITING_COLOR) }, 4)
        } else {
            drawScrollingLabel(g, timeLabel(cur, dur), 4)
        }

        val previewTarget = if (previewFrame != null && active && dur > 0 && (isHovered || dragging)) 1f else 0f
        previewFade += (previewTarget - previewFade) * FADE_SPEED
        if (previewFade > 0.01f) {
            val hoverNanos = if (dragging) dragTargetNanos else positionFromMouse(mouseX.toDouble(), dur)
            previewFrame?.invoke(hoverNanos)?.let { drawPreview(g, it, mouseX, hoverNanos, dur, previewFade) }
        }
    }

    /**
     * Draws the scrub-preview thumbnail above the bar, centered on [mouseX] and clamped to the
     * screen. The on-screen box stays at [DISPLAY_WIDTH] x [DISPLAY_HEIGHT] regardless of the
     * texture's actual (larger) resolution — vanilla `blit`'s `(u, v, width, height, textureWidth,
     * textureHeight)` overload samples 1:1 texel-for-pixel rather than scaling to fit, so shrinking
     * the box is done with a pose-stack scale around a full-resolution blit instead (GPU bilinear
     * downsample, keeping the extra source detail instead of just cropping to the box size).
     */
    private fun drawPreview(g: GuiGraphicsCompat, texture: Identifier, mouseX: Int, hoverNanos: Long, dur: Long, fade: Float) {
        val textureW = ScrubPreview.FRAME_WIDTH
        val textureH = ScrubPreview.FRAME_HEIGHT
        val boxW = DISPLAY_WIDTH + 4
        val boxH = DISPLAY_HEIGHT + 14
        // Clamped to the screen, not the (possibly narrower-than-the-box) widget bounds — coerceIn
        // over widget bounds alone throws when width < boxW (min > max).
        val screenW = Minecraft.getInstance().window.guiScaledWidth
        val minX = 0
        val maxX = (screenW - boxW).coerceAtLeast(minX)
        val boxX = (mouseX - boxW / 2).coerceIn(minX, maxX)
        val boxY = y - boxH - 4

        val boxAlpha = (0xE0 * fade).toInt().coerceIn(0, 0xE0)
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, (boxAlpha shl 24) or 0x101010)

        val scaleX = DISPLAY_WIDTH.toFloat() / textureW
        val scaleY = DISPLAY_HEIGHT.toFloat() / textureH
        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate((boxX + 2).toFloat(), (boxY + 2).toFloat())
        matrices.scale(scaleX, scaleY)
        g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, textureW, textureH, textureW, textureH, ARGB.white(fade))
        matrices.popMatrix()
        //?} else
        /*matrices.pushPose()
        matrices.translate((boxX + 2).toDouble(), (boxY + 2).toDouble(), 0.0)
        matrices.scale(scaleX, scaleY, 1f)
        g.blit(texture, 0, 0, 0f, 0f, textureW, textureH, textureW, textureH)
        matrices.popPose()*/

        val font = Minecraft.getInstance().font
        val label = UiText.formatTime(hoverNanos)
        val labelX = boxX + (boxW - font.width(label)) / 2
        val textAlpha = (0xFF * fade).toInt().coerceIn(0, 0xFF)
        g.drawText(font, label, labelX, boxY + DISPLAY_HEIGHT + 4, (textAlpha shl 24) or 0xFFFFFF, false)
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

    //? if >=26 {
    override fun requestWidgetCursor(g: GuiGraphicsExtractor) {
        if (active && duration() > 0 && isHovered) {
            g.requestCursor(CursorTypes.RESIZE_EW)
        } else {
            super.requestWidgetCursor(g)
        }
    }
    //?}

    // NeoForge reroutes mouseClicked to a Neo-only 3-arg onClick that Fabric lacks, so the legacy
    // (1.21.1) branch overrides mouseClicked itself so drag-to-seek starts on both platforms. onDrag
    // is unchanged across platforms.
    //? if >=1.21.11 {
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
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        val dur = duration()
        if (dur <= 0) return false
        dragTargetNanos = positionFromMouse(mouseX, dur)
        dragging = true
        return true
    }

    override fun onDrag(mouseX: Double, mouseY: Double, dragX: Double, dragY: Double) {
        super.onDrag(mouseX, mouseY, dragX, dragY)
        if (!dragging || !active) return
        val dur = duration()
        if (dur <= 0) return
        dragTargetNanos = positionFromMouse(mouseX, dur)
    }*/

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
        private const val DISPLAY_WIDTH = 106
        private const val DISPLAY_HEIGHT = 60
        private const val GHOST_HANDLE_ALPHA = 0.45f
        private const val FADE_SPEED = 0.35f

        /** Dim grey used for [waitingLabel] text (matches the disabled time-label color). */
        private const val WAITING_COLOR = 0xFFA0A0A0.toInt()

        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
