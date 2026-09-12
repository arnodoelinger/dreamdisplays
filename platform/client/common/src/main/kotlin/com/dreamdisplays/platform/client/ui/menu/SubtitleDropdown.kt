package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.stream.model.SubtitleTrack
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
//? if <1.21.11 {
import com.dreamdisplays.platform.client.ui.enableScissorPoseAware
//?}
import com.dreamdisplays.platform.client.ui.kit.*
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The popup that opens above the subtitles button, listing "Off" plus the current video's selectable
 * subtitle tracks.
 *
 * Absolutely same visual / interaction style and pop-out animation as [AudioTrackDropdown].
 */
class SubtitleDropdown(
    private val getTracks: () -> List<SubtitleTrack>,
    private val currentLang: () -> String?,
    private val onSelect: (SubtitleTrack?) -> Unit,
) {
    var visible: Boolean = false

    private var items: List<SubtitleTrack?> = listOf(null)

    private var scrollIndex = 0

    private var scrollPx = 0f

    private var rect = UiRect(0, 0, WIDTH, ITEM_H)

    private var animProgress = 0f
    private var lastFrameNanos = 0L

    private var visibleCount = 0

    private var sbPaged = false
    private var sbColLeft = 0
    private var sbColRight = 0
    private var sbTrackTop = 0
    private var sbTrackBottom = 0

    private var draggingScrollbar = false

    fun toggle() {
        visible = !visible
        if (!visible) return
        items = listOf(null) + getTracks()
        val maxIndex = maxScrollIndex()
        val activeIdx = items.indexOfFirst { it?.lang == currentLang() }.takeIf { it >= 0 } ?: 0
        scrollIndex = (activeIdx - MAX_VISIBLE / 2).coerceIn(0, maxIndex)
        scrollPx = scrollIndex * ITEM_H.toFloat()
    }

    fun hide() {
        visible = false
        draggingScrollbar = false
    }

    private fun maxScrollIndex(): Int = (items.size - MAX_VISIBLE).coerceAtLeast(0)

    private fun label(track: SubtitleTrack?): String =
        track?.displayName ?: track?.lang ?: Component.translatable("dreamdisplays.ui.subtitles_off").string

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

        val targetScrollPx = scrollIndex * ITEM_H.toFloat()
        val scrollDiff = targetScrollPx - scrollPx
        scrollPx = if (abs(scrollDiff) < 0.05f) targetScrollPx else scrollPx + scrollDiff * minOf(1f, dt * SCROLL_EASE_RATE)

        visibleCount = items.size.coerceIn(1, MAX_VISIBLE)
        val paged = items.size > MAX_VISIBLE
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
        val hoveredIndex = if (visible && mouseX in rect.x..rect.right && mouseY in rowTop until rowBottom)
            ((mouseY - rowTop + scrollPx) / ITEM_H).toInt() else -1
        val activeLang = currentLang()

        val innerLeft = rect.x + BORDER
        val scrollbarRight = rect.right - SCROLLBAR_MARGIN

        val font = Minecraft.getInstance().font
        val fy = (ITEM_H - font.lineHeight) / 2
        val textRight = if (paged) scrollbarRight - SCROLLBAR_W - 2 else rect.right - BORDER
        //? if >=1.21.11 {
        g.enableScissor(rect.x, rowTop, rect.right, rowBottom)
        //?} else
        /*g.enableScissorPoseAware(rect.x, rowTop, rect.right, rowBottom)*/
        val firstVisible = (scrollPx / ITEM_H).toInt().coerceAtLeast(0)
        val lastVisible = ((scrollPx + (rowBottom - rowTop)) / ITEM_H).toInt().coerceAtMost(items.size - 1)
        for (i in firstVisible..lastVisible) {
            val track = items[i]
            val itemY = (rowTop - scrollPx + ITEM_H * i).roundToInt()
            val isActive = track?.lang == activeLang
            when {
                i == hoveredIndex -> {
                    g.fill(innerLeft, itemY, textRight, itemY + ITEM_H, scaleAlpha(UiTheme.HOVER_FILL, animProgress))
                    g.drawOutline(
                        UiRect(innerLeft, itemY, textRight - innerLeft, ITEM_H),
                        scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress)
                    )
                }
                isActive -> g.fill(innerLeft, itemY, textRight, itemY + ITEM_H, scaleAlpha(UiTheme.ACTIVE_ROW_FILL, animProgress))
            }
            val color =
                scaleAlpha(if (i == hoveredIndex || isActive) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, animProgress)
            val textW = textRight - innerLeft - 6
            g.drawText(font, UiText.trim(font, label(track), textW), innerLeft + 4, itemY + fy, color, false)
        }
        g.disableScissor()

        sbPaged = paged
        if (paged) {
            sbColLeft = scrollbarRight - SCROLLBAR_W
            sbColRight = scrollbarRight
            sbTrackTop = rowTop
            sbTrackBottom = rowBottom
            drawScrollbar(g, animProgress, scrollbarRight, rowTop, rowBottom)
        }

        //? if >=1.21.11 {
        if (visible && hoveredIndex >= 0 && animProgress > 0.5f) g.requestCursor(CursorTypes.POINTING_HAND)
        val overScrollbar = sbPaged && mouseX in (sbColLeft - SB_GRAB)..(sbColRight + 1) &&
                mouseY in sbTrackTop..sbTrackBottom
        if (visible && (draggingScrollbar || overScrollbar) && animProgress > 0.5f) g.requestCursor(CursorTypes.RESIZE_NS)
        //?}

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
    }

    private fun drawScrollbar(g: GuiGraphicsCompat, alpha: Float, right: Int, top: Int, bottom: Int) {
        val trackX = right - SCROLLBAR_W
        val trackH = bottom - top
        g.fill(trackX, top, trackX + 2, bottom, scaleAlpha(UiTheme.SCROLLBAR_TRACK, alpha))

        val maxIndex = maxScrollIndex()
        val thumbH = max(MIN_THUMB_H, trackH * visibleCount / items.size)
        val travel = trackH - thumbH
        val thumbY = top + if (maxIndex > 0) (travel * scrollPx / (maxIndex * ITEM_H)).roundToInt() else 0
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, scaleAlpha(UiTheme.SCROLLBAR_THUMB, alpha))
    }

    fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        if (sbPaged && mx in (sbColLeft - SB_GRAB)..(sbColRight + 1) && my in sbTrackTop..sbTrackBottom) {
            draggingScrollbar = true
            scrollToY(my)
            return true
        }
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        visible = false
        if (!inside || items.isEmpty()) return false
        val rowTop = rect.y + PAD_V
        val index = ((my - rowTop + scrollPx) / ITEM_H).toInt().coerceIn(0, items.size - 1)
        val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        onSelect(items[index])
        return true
    }

    fun handleScroll(mx: Int, my: Int, scrollY: Double): Boolean {
        if (!visible || items.size <= MAX_VISIBLE) return false
        if (!rect.contains(mx, my)) return false
        val delta = if (scrollY > 0) -1 else if (scrollY < 0) 1 else 0
        if (delta != 0) scrollIndex = (scrollIndex + delta).coerceIn(0, maxScrollIndex())
        return true
    }

    fun handleDrag(my: Int): Boolean {
        if (!draggingScrollbar) return false
        scrollToY(my)
        return true
    }

    fun handleRelease(): Boolean {
        val was = draggingScrollbar
        draggingScrollbar = false
        return was
    }

    private fun scrollToY(my: Int) {
        val maxIndex = maxScrollIndex()
        if (maxIndex <= 0) {
            scrollIndex = 0
            return
        }
        val trackH = sbTrackBottom - sbTrackTop
        val thumbH = max(MIN_THUMB_H, trackH * visibleCount / items.size)
        val travel = trackH - thumbH
        if (travel <= 0) {
            scrollIndex = 0
            return
        }
        val rel = (my - sbTrackTop - thumbH / 2).coerceIn(0, travel)
        scrollIndex = ((rel.toFloat() / travel) * maxIndex).roundToInt().coerceIn(0, maxIndex)
    }

    companion object {
        private const val WIDTH = 90
        private const val ITEM_H = 18
        private const val MAX_VISIBLE = 7
        private const val BORDER = 3
        private const val PAD_V = 2
        private const val SCROLLBAR_W = 5
        private const val MIN_THUMB_H = 6
        private const val SCROLLBAR_MARGIN = 0

        private const val SB_GRAB = 3

        private const val SCROLL_EASE_RATE = 8f
    }
}
