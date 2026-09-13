package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.api.media.search.model.MediaChapter
import com.dreamdisplays.platform.client.render.ScrubPreview
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.platform.client.ui.widgets.SeekBar.Companion.DISPLAY_HEIGHT
import com.dreamdisplays.platform.client.ui.widgets.SeekBar.Companion.DISPLAY_WIDTH
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
//? if >=1.21.11 {
import net.minecraft.util.ARGB
//?}
import net.minecraft.util.Mth

/**
 * Playback progress bar with drag-to-seek. Position and duration are pulled from lambdas every frame; the seek only
 * commits on release.
 */
class SeekBar(
    private val current: () -> Long,
    private val duration: () -> Long,
    private val previewFrame: ((Long) -> Identifier?)? = null,
    private val waitingLabel: (() -> String?)? = null,
    private val scheduleLabel: (() -> String?)? = null,
    statusLabels: List<() -> String?> = emptyList(),
    private val chapters: () -> List<MediaChapter> = { emptyList() },
    private val onSeek: (Long) -> Unit,
) : UiWidget(Component.empty()) {

    private var sliderFocused = false
    private var dragging = false
    private var dragTargetNanos = 0L
    private var hoverFade = 0f
    private var previewFade = 0f


    /**
     * A suffix behind the time label that grows in and shrinks away character by character. Its text
     * is sampled every frame from [source]; a null sample retracts it rather than cutting it away, so
     * the last text is kept around to shrink from.
     */
    private class RevealingSuffix(private val source: (() -> String?)?) {
        private var lastText: String? = source?.invoke()

        /** 0..1 reveal progress, eased toward the presence of a current sample in [advance]. */
        private var reveal = if (lastText != null) 1f else 0f

        /** Samples the source for this frame and eases the reveal toward it. */
        fun advance() {
            val now = source?.invoke()
            if (now != null) lastText = now
            reveal += ((if (now != null) 1f else 0f) - reveal) * FADE_SPEED
            if (reveal < 0.01f) reveal = 0f
            if (reveal > 0.99f) reveal = 1f
        }

        /** Appends however much of the suffix is revealed this frame to [base], which it may return as-is. */
        fun appendTo(base: MutableComponent): MutableComponent {
            val text = lastText
            if (reveal <= 0f || text == null) return base
            val full = " • $text"
            val chars = (full.length * reveal).toInt().coerceIn(0, full.length)
            return base.append(
                Component.literal(full.substring(0, chars)).withStyle { it.withColor(WAITING_COLOR) },
            )
        }
    }

    /** Countdown to a scheduled start / pause. */
    private val scheduleSuffix = RevealingSuffix(scheduleLabel)

    /**
     * Transient "applying..." hints for changes that outlive the click that started them. One suffix
     * per source rather than one shared slot, so concurrent changes stack up instead of hiding each
     * other — several of these can genuinely be in flight at once.
     */
    private val statusSuffixes = statusLabels.map { RevealingSuffix(it) }

    override fun handlesWholeWidgetCursor(): Boolean = false

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val dur = duration()
        val cur = if (dragging) dragTargetNanos else current()
        val value = if (dur > 0) Mth.clamp(cur / dur.toDouble(), 0.0, 1.0) else 0.0

        val hoverTarget = if (active && !dragging && dur > 0 && isHovered) 1f else 0f
        hoverFade += (hoverTarget - hoverFade) * FADE_SPEED


        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, trackSprite(), x, y, width, height)
        //?} else
        /*g.blitSprite(trackSprite(), x, y, width, height)*/
        val handleX = x + (value * (width - 8).toDouble()).toInt()
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, handleSprite(), handleX, y, 8, height)
        //?} else
        /*g.blitSprite(handleSprite(), handleX, y, 8, height)*/

        if (hoverFade > 0.01f) {
            val hoverPct = Mth.clamp((mouseX - (x + 4).toDouble()) / (width - 8).toDouble(), 0.0, 1.0)
            val hoverX = x + (hoverPct * (width - 8)).toInt()
            //? if >=1.21.11 {
            g.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                HANDLE,
                hoverX,
                y,
                8,
                height,
                ARGB.white(GHOST_HANDLE_ALPHA * hoverFade)
            )
            //?} else
            /*g.blitSprite(HANDLE, hoverX, y, 8, height)*/
        }

        scheduleSuffix.advance()
        statusSuffixes.forEach { it.advance() }

        val waiting = waitingLabel?.invoke()
        val base = if (waiting != null) {
            Component.literal(waiting).copy().withStyle { it.withColor(WAITING_COLOR) }
        } else {
            timeLabel(cur, dur)
        }

        var shown = scheduleSuffix.appendTo(base.copy())
        statusSuffixes.forEach { shown = it.appendTo(shown) }
        drawScrollingLabel(g, shown, 4)

        val previewTarget = if (previewFrame != null && active && dur > 0 && (isHovered || dragging)) 1f else 0f
        previewFade += (previewTarget - previewFade) * FADE_SPEED
        if (previewFade > 0.01f) {
            val hoverNanos = if (dragging) dragTargetNanos else positionFromMouse(mouseX.toDouble(), dur)
            previewFrame?.invoke(hoverNanos)?.let { drawPreview(g, it, mouseX, hoverNanos, dur, previewFade) }
        }
        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active && dur > 0) CursorTypes.RESIZE_EW else CursorTypes.NOT_ALLOWED)
        }
        //?}
    }

    /** The chapter covering [nanos], or null when the video has none. */
    private fun chapterAt(nanos: Long): MediaChapter? =
        chapters().lastOrNull { it.startSeconds * 1_000_000_000L <= nanos }

    /**
     * Draws the scrub-preview thumbnail above the bar, centered on [mouseX] and clamped to the screen.
     * The on-screen timestamp tracks the hover position, with the chapter it lands in named above the frame.
     */
    private fun drawPreview(
        g: GuiGraphicsCompat,
        texture: Identifier,
        mouseX: Int,
        hoverNanos: Long,
        dur: Long,
        fade: Float
    ) {
        val font = Minecraft.getInstance().font
        val textureW = ScrubPreview.FRAME_WIDTH
        val textureH = ScrubPreview.FRAME_HEIGHT
        val boxW = DISPLAY_WIDTH + 4
        val chapterTitle = chapterAt(hoverNanos)?.title
        val headerH = if (chapterTitle != null) font.lineHeight + 3 else 0
        val boxH = DISPLAY_HEIGHT + 14 + headerH
        // Clamped to the screen, not the (possibly narrower-than-the-box) widget bounds — coerceIn
        // over widget bounds alone throws when width < boxW (min > max).
        val screenW = Minecraft.getInstance().window.guiScaledWidth
        val minX = 0
        val maxX = (screenW - boxW).coerceAtLeast(minX)
        val boxX = (mouseX - boxW / 2).coerceIn(minX, maxX)
        val boxY = y - boxH - 4
        val frameY = boxY + 2 + headerH

        val boxAlpha = (0xE0 * fade).toInt().coerceIn(0, 0xE0)
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, (boxAlpha shl 24) or 0x101010)

        if (chapterTitle != null) {
            val shown = UiText.trim(font, chapterTitle, boxW - 6)
            val textAlpha = (0xFF * fade).toInt().coerceIn(0, 0xFF)
            g.drawText(
                font, shown, boxX + (boxW - font.width(shown)) / 2, boxY + 3,
                (textAlpha shl 24) or CHAPTER_LABEL_RGB, false,
            )
        }

        val scaleX = DISPLAY_WIDTH.toFloat() / textureW
        val scaleY = DISPLAY_HEIGHT.toFloat() / textureH
        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate((boxX + 2).toFloat(), frameY.toFloat())
        matrices.scale(scaleX, scaleY)
        g.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            0,
            0,
            0f,
            0f,
            textureW,
            textureH,
            textureW,
            textureH,
            ARGB.white(fade)
        )
        matrices.popMatrix()
        //?} else
        /*matrices.pushPose()
        matrices.translate((boxX + 2).toDouble(), frameY.toDouble(), 0.0)
        matrices.scale(scaleX, scaleY, 1f)
        g.blit(texture, 0, 0, 0f, 0f, textureW, textureH, textureW, textureH)
        matrices.popPose()*/

        val label = UiText.formatTime(hoverNanos)
        val labelX = boxX + (boxW - font.width(label)) / 2
        val labelAlpha = (0xFF * fade).toInt().coerceIn(0, 0xFF)
        g.drawText(font, label, labelX, frameY + DISPLAY_HEIGHT + 2, (labelAlpha shl 24) or 0xFFFFFF, false)
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
        private const val CHAPTER_LABEL_RGB = 0xAAAAAA
        private const val WAITING_COLOR = 0xFFA0A0A0.toInt()

        private val TRACK = Identifier.withDefaultNamespace("widget/slider")
        private val TRACK_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_highlighted")
        private val HANDLE = Identifier.withDefaultNamespace("widget/slider_handle")
        private val HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted")
    }
}
