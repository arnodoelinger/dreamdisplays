package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.DROPDOWN_SPRITE
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.drawPanelSprite
import com.dreamdisplays.platform.client.ui.kit.scaleAlpha
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import kotlin.math.max

/**
 * The popup that opens above the audio-track button, listing the current video's selectable audio
 * tracks. Same visual / interaction pattern as [PopoutDropdown], but its items are dynamic — the
 * track list is resolved per video — so it refreshes from [getTracks] every time it becomes visible
 * and highlights the currently playing track.
 *
 * @param getTracks supplies the audio tracks available for the current video.
 * @param currentUrl the resolved URL of the track currently playing (highlighted in the list).
 * @param onSelect invoked with the track the user picked.
 */
class AudioTrackDropdown(
    private val getTracks: () -> List<MediaStream>,
    private val currentUrl: () -> String,
    private val onSelect: (MediaStream) -> Unit,
) {
    var visible: Boolean = false

    /** Tracks shown in display order; refreshed from [getTracks] each time the dropdown opens. */
    private var items: List<MediaStream> = emptyList()

    /** Index of the first item in the visible page; scrolled via [handleScroll] or the drag-thumb. */
    private var scrollIndex = 0

    private var rect = UiRect(0, 0, WIDTH, ITEM_H)

    /** Appear / disappear progress in 0..1, eased the same way as [PopoutDropdown]. */
    private var animProgress = 0f
    private var lastFrameNanos = 0L

    /** Number of rows currently on screen (`min(items.size, MAX_VISIBLE)`), used by hit-testing. */
    private var visibleCount = 0

    /** Toggles visibility; snapshots the current track list and centers the page on the active track. */
    fun toggle() {
        visible = !visible
        if (!visible) return
        items = getTracks()
        val maxIndex = maxScrollIndex()
        val activeIdx = items.indexOfFirst { it.url == currentUrl() }.takeIf { it >= 0 } ?: 0
        scrollIndex = (activeIdx - MAX_VISIBLE / 2).coerceIn(0, maxIndex)
    }

    /** Hides the dropdown. */
    fun hide() {
        visible = false
    }

    /** Highest valid [scrollIndex] for the current item count. */
    private fun maxScrollIndex(): Int = (items.size - MAX_VISIBLE).coerceAtLeast(0)

    /** Label for [stream]: its track name, else its language code, else a 1-based index fallback. */
    private fun label(stream: MediaStream, index: Int): String =
        stream.audioTrackName ?: stream.audioTrackLang ?: "Track ${index + 1}"

    /**
     * Draws the dropdown anchored above ([anchorCenterX], [anchorY]), horizontally centered on that
     * point (e.g. the button's own center) rather than growing out from one of its edges — easing
     * in / out of [visible].
     */
    fun draw(g: GuiGraphicsCompat, anchorCenterX: Int, anchorY: Int, mouseX: Int, mouseY: Int) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        val target = if (visible) 1f else 0f
        animProgress += (target - animProgress) * minOf(1f, dt * 12f)
        if (animProgress < 0.01f) {
            animProgress = 0f
            return
        }

        visibleCount = items.size.coerceIn(1, MAX_VISIBLE)
        val paged = items.size > MAX_VISIBLE
        // PAD_V keeps the top/bottom rows from touching the frame's own border, the same way the
        // left/right content inset does — without it the first/last row's highlight box visibly
        // overlaps the sprite's border artwork.
        val height = ITEM_H * visibleCount + PAD_V * 2
        rect = UiRect(anchorCenterX - WIDTH / 2, anchorY - height - 2, WIDTH, height)

        val scale = 0.85f + 0.15f * animProgress
        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate(rect.centerX.toFloat(), rect.centerY.toFloat())
        matrices.scale(scale, scale)
        matrices.translate(-rect.centerX.toFloat(), -rect.centerY.toFloat())
        //?} else
        /*matrices.pushPose()
        matrices.translate(rect.centerX.toDouble(), rect.centerY.toDouble(), 0.0)
        matrices.scale(scale, scale, 1f)
        matrices.translate(-rect.centerX.toDouble(), -rect.centerY.toDouble(), 0.0)*/

        g.drawPanelSprite(rect, DROPDOWN_SPRITE, animProgress)

        val rowTop = rect.y + PAD_V
        val rowBottom = rect.bottom - PAD_V
        val hovered = if (visible && mouseX in rect.x..rect.right && mouseY in rowTop until rowBottom)
            (mouseY - rowTop) / ITEM_H else -1
        val activeUrl = currentUrl()

        // DROPDOWN_SPRITE is a nine-slice with a 3px border; text/highlights stay clear of it via
        // BORDER. The scrollbar instead hugs the border closely (SCROLLBAR_MARGIN) so it visibly
        // touches the frame's inner edge rather than floating with a gap.
        val innerLeft = rect.x + BORDER
        val scrollbarRight = rect.right - SCROLLBAR_MARGIN

        val font = Minecraft.getInstance().font
        val fy = rowTop + (ITEM_H - font.lineHeight) / 2
        val textRight = if (paged) scrollbarRight - SCROLLBAR_W - 2 else rect.right - BORDER
        for (row in 0 until visibleCount) {
            val i = scrollIndex + row
            val stream = items[i]
            val itemY = rowTop + ITEM_H * row
            val isActive = stream.url == activeUrl
            when {
                row == hovered -> {
                    g.fill(innerLeft, itemY, textRight, itemY + ITEM_H, scaleAlpha(UiTheme.HOVER_FILL, animProgress))
                    g.drawOutline(UiRect(innerLeft, itemY, textRight - innerLeft, ITEM_H), scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress))
                }
                // The playing track keeps a faint accent tint so it's always visible, even unhovered
                isActive -> g.fill(innerLeft, itemY, textRight, itemY + ITEM_H, scaleAlpha(ACTIVE_FILL, animProgress))
            }
            val color = scaleAlpha(if (row == hovered || isActive) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, animProgress)
            val textW = textRight - innerLeft - 6
            g.drawText(font, UiText.trim(font, label(stream, i), textW), innerLeft + 4, fy + ITEM_H * row, color, false)
        }

        if (paged) drawScrollbar(g, animProgress, scrollbarRight, rowTop, rowBottom)

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
    }

    /** Draws a thin track + thumb whose right edge sits at [right], between [top] and [bottom]. */
    private fun drawScrollbar(g: GuiGraphicsCompat, alpha: Float, right: Int, top: Int, bottom: Int) {
        val trackX = right - SCROLLBAR_W
        val trackH = bottom - top
        g.fill(trackX, top, trackX + 2, bottom, scaleAlpha(UiTheme.SCROLLBAR_TRACK, alpha))

        val maxIndex = maxScrollIndex()
        val thumbH = max(MIN_THUMB_H, trackH * visibleCount / items.size)
        val travel = trackH - thumbH
        val thumbY = top + if (maxIndex > 0) travel * scrollIndex / maxIndex else 0
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, scaleAlpha(UiTheme.SCROLLBAR_THUMB, alpha))
    }

    /**
     * Handles a left click while visible: picks a track if the click is inside, always hides.
     * Returns true if a track was picked (the click is consumed).
     */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        visible = false
        if (!inside || items.isEmpty()) return false
        val rowTop = rect.y + PAD_V
        val row = ((my - rowTop) / ITEM_H).coerceIn(0, visibleCount - 1)
        val index = (scrollIndex + row).coerceIn(0, items.size - 1)
        val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        onSelect(items[index])
        return true
    }

    /**
     * Handles a mouse-wheel tick while visible and hovered over the panel: pages [scrollIndex] by
     * one row per notch. Returns true if the event was consumed (so the screen behind doesn't scroll).
     */
    fun handleScroll(mx: Int, my: Int, scrollY: Double): Boolean {
        if (!visible || items.size <= MAX_VISIBLE) return false
        if (!rect.contains(mx, my)) return false
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta != 0) scrollIndex = (scrollIndex + delta).coerceIn(0, maxScrollIndex())
        return true
    }

    companion object {
        private const val WIDTH = 90
        private const val ITEM_H = 18

        /** Rows shown at once before the list pages; keeps the dropdown from spilling off-screen. */
        private const val MAX_VISIBLE = 7

        /** DROPDOWN_SPRITE's nine-slice border thickness (see dropdown.png.mcmeta); text/highlights inset by this. */
        private const val BORDER = 3

        /** Small vertical breathing room above the first row and below the last, so their highlight
         *  boxes don't touch the frame's top/bottom border the way BORDER keeps them off the sides. */
        private const val PAD_V = 2

        private const val SCROLLBAR_W = 5
        private const val MIN_THUMB_H = 6

        /** Scrollbar's own inset from the right edge — deliberately smaller than BORDER so the bar
         *  reads as touching the frame's inner edge instead of floating with a visible gap. */
        private const val SCROLLBAR_MARGIN = 1

        /** Faint blue tint marking the currently playing track (accent at low alpha). */
        private const val ACTIVE_FILL = 0x334A90E2
    }
}
