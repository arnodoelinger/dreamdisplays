package com.dreamdisplays.client.ui.widgets

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.drawText
import com.dreamdisplays.client.ui.kit.UiRect
import com.dreamdisplays.client.ui.kit.UiText
import com.dreamdisplays.client.ui.kit.UiTheme
import com.dreamdisplays.client.ui.kit.UiWidget
import com.dreamdisplays.client.ui.kit.drawOutline
import com.dreamdisplays.client.ui.kit.renderChild
import com.dreamdisplays.media.api.MediaSearchResult
import com.dreamdisplays.ytdlp.Thumbnails
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Scrollable search / related-videos panel: a search row (edit box + clear + search buttons) above a
 * strip of video cards, laid out vertically (sidebar) or horizontally (bottom strip). All async
 * loading lives in [SuggestionsController]; this widget is the view.
 *
 * @param onPick invoked when the user clicks a result card.
 *
 * @since 1.8.0
 */
class SuggestionsPanel(
    private val onPick: (MediaSearchResult) -> Unit,
) : UiWidget(Component.translatable("dreamdisplays.button.suggestions")) {

    private val controller = SuggestionsController().also { it.onResults = { scrollOffset = 0 } }
    private val searchBox: EditBox
    private val clearButton: IconButton
    private val searchButton: IconButton

    private var scrollOffset: Int = 0
    private var hoveredCard: Int = -1
    private var vertical: Boolean = false
    private var compactCards: Boolean = false
    private var lastStripH: Int = CARD_H

    init {
        val f = Minecraft.getInstance().font
        searchBox = EditBox(
            f, 0, 0, 100, SEARCH_H,
            Component.translatable("dreamdisplays.suggestions.search"),
        )
        searchBox.setHint(Component.translatable("dreamdisplays.suggestions.search"))
        searchBox.setMaxLength(200)
        clearButton = IconButton(icon = { IconButton.modIcon("cross") }, margin = 4) {
            searchBox.value = ""
            searchBox.isFocused = true
        }
        searchButton = IconButton(icon = { IconButton.modIcon("search") }, margin = 4) {
            controller.runSearch(searchBox.value)
        }
    }

    /** Switches between vertical sidebar and horizontal strip card layout. */
    fun setVertical(v: Boolean) {
        vertical = v
    }

    /** Toggles compact cards (thumbnail only, no title/meta text). */
    fun setCompactCards(c: Boolean) {
        compactCards = c
    }

    /** Shows videos related to [videoId]; clears the panel when null. */
    fun setRelatedTo(videoId: String?) = controller.setRelatedTo(videoId)

    private fun searchRowY(): Int = y + 10 + HEADER_H + 6
    private fun stripTop(): Int = searchRowY() + SEARCH_H + 8
    private fun stripBottom(): Int = y + height - 10
    private fun stripLeft(): Int = x + 10
    private fun stripRight(): Int = x + width - 10

    /** Positions the search box and its action buttons for the current panel rect. */
    private fun layoutChildren() {
        searchBox.x = x + 10
        searchBox.y = searchRowY()
        searchBox.width = width - 20 - (ACTION_W + ACTION_GAP) * 2
        clearButton.place(UiRect(x + width - 10 - ACTION_W * 2 - ACTION_GAP, searchRowY(), ACTION_W, SEARCH_H))
        searchButton.place(UiRect(x + width - 10 - ACTION_W, searchRowY(), ACTION_W, SEARCH_H))
    }

    /** Card width for the current orientation/viewport. */
    private fun cardW(viewportW: Int): Int =
        if (vertical) max(CARD_W, viewportW) else dynCardW()

    /** Thumbnail height for the current orientation/viewport. */
    private fun thumbH(viewportW: Int): Int =
        if (vertical) max(THUMB_H, (cardW(viewportW) * 180.0 / 320.0).toInt()) else dynThumbH()

    /** Card height for the current orientation/viewport. */
    private fun cardH(viewportW: Int): Int = when {
        !vertical -> dynCardH()
        compactCards -> THUMB_H + 4
        else -> thumbH(viewportW) + CARD_TEXT_H
    }

    private fun dynThumbH(): Int {
        val available = lastStripH - 2 - 3 - CARD_TEXT_H - 2
        return max(30, min(THUMB_H, available))
    }

    private fun dynCardH(): Int = dynThumbH() + 2 + 3 + CARD_TEXT_H + 2

    private fun dynCardW(): Int {
        val th = dynThumbH()
        if (th >= THUMB_H) return CARD_W
        return max(80, (th * CARD_W / THUMB_H.toDouble()).toInt())
    }

    /** Total scrollable content extent along the scroll axis. */
    private fun contentExtent(viewportW: Int): Int {
        val per = (if (vertical) cardH(viewportW) else cardW(viewportW)) + CARD_GAP
        return controller.cards.size * per - CARD_GAP
    }

    /** Maximum scroll offset for the current viewport. */
    private fun maxScroll(viewportW: Int, viewportH: Int): Int =
        max(0, contentExtent(viewportW) - if (vertical) viewportH else viewportW)

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        val r = UiRect(x, y, width, height)
        g.fill(r.x, r.y, r.right, r.bottom, UiTheme.SUGGESTIONS_BG)
        g.drawOutline(r, UiTheme.SUGGESTIONS_BORDER)

        val f = Minecraft.getInstance().font
        g.drawText(f, message, x + 10, y + 10, UiTheme.TEXT_PRIMARY, false)

        layoutChildren()
        searchBox.renderChild(g, mouseX, mouseY, partialTick)
        clearButton.renderChild(g, mouseX, mouseY, partialTick)
        searchButton.renderChild(g, mouseX, mouseY, partialTick)

        val stripTop = stripTop()
        val stripBottom = stripBottom()
        val stripH = stripBottom - stripTop
        if (stripH < 40) return
        lastStripH = stripH

        controller.statusKey?.let { key ->
            val base = Component.translatable(key).string
            val msg = if (controller.isLoading) {
                val elapsed = maxOf(0L, (System.currentTimeMillis() - controller.loadStartedAtMs) / 1000L)
                base.replace(Regex("\\.+$"), "") + " • " + elapsed + "s"
            } else base
            g.drawText(f, msg, x + 10, stripTop + 6, UiTheme.TEXT_SECONDARY, false)
            return
        }

        val stripLeft = stripLeft()
        val stripRight = stripRight()
        val viewportW = stripRight - stripLeft
        val viewportH = stripBottom - stripTop
        val cw = cardW(viewportW)
        val th = thumbH(viewportW)
        val ch = cardH(viewportW)
        val maxOff = maxScroll(viewportW, viewportH)
        scrollOffset = scrollOffset.coerceIn(0, maxOff)

        val cards = controller.cards
        g.enableScissor(stripLeft, stripTop, stripRight, stripBottom)
        hoveredCard = -1
        val rowY = if (vertical) 0 else stripTop + max(0, (viewportH - ch) / 2)
        var pos = (if (vertical) stripTop else stripLeft) - scrollOffset
        for (i in cards.indices) {
            val info = cards[i]
            val cardX = if (vertical) stripLeft else pos
            val cardY = if (vertical) pos else rowY
            val visibleOnAxis = if (vertical) {
                cardY + ch >= stripTop && cardY <= stripBottom
            } else {
                cardX + cw >= stripLeft && cardX <= stripRight
            }
            if (visibleOnAxis) {
                val hover = mouseX >= max(cardX, stripLeft) && mouseX < min(cardX + cw, stripRight) &&
                        mouseY >= max(cardY, stripTop) && mouseY < min(cardY + ch, stripBottom)
                if (hover) hoveredCard = i
                drawCard(g, f, info, cardX, cardY, cw, th, ch, hover)
                if (Thumbnails.get(info.id) == null)
                    Thumbnails.request(info.id, info.getThumbnailUrl())
            }
            pos += (if (vertical) ch else cw) + CARD_GAP
        }
        g.disableScissor()

        drawScrollbar(g, stripLeft, stripTop, stripRight, stripBottom, maxOff, viewportW, viewportH)
    }

    /** Draws the thin scrollbar along the scroll axis when content overflows. */
    private fun drawScrollbar(
        g: GuiGraphicsCompat,
        stripLeft: Int, stripTop: Int, stripRight: Int, stripBottom: Int,
        maxOff: Int, viewportW: Int, viewportH: Int,
    ) {
        if (maxOff <= 0) return
        if (vertical) {
            val content = maxOff + viewportH
            val barX = stripRight + 1
            g.fill(barX, stripTop, barX + 2, stripBottom, 0xFF202020.toInt())
            val barH = max(20, (viewportH.toFloat() / content * viewportH).toInt())
            val barY = stripTop + (scrollOffset.toFloat() / maxOff * (viewportH - barH)).toInt()
            g.fill(barX, barY, barX + 2, barY + barH, 0xFF808080.toInt())
        } else {
            val content = maxOff + viewportW
            val barY = stripBottom + 1
            g.fill(stripLeft, barY, stripRight, barY + 2, 0xFF202020.toInt())
            val barW = max(20, (viewportW.toFloat() / content * viewportW).toInt())
            val barX = stripLeft + (scrollOffset.toFloat() / maxOff * (viewportW - barW)).toInt()
            g.fill(barX, barY, barX + barW, barY + 2, 0xFF808080.toInt())
        }
    }

    /** Draws one result card: hover-pulsing background, thumbnail, NEW/duration tags, title, and meta. */
    private fun drawCard(
        g: GuiGraphicsCompat, f: Font, info: MediaSearchResult,
        x: Int, y: Int, w: Int, thumbH: Int, cardH: Int, hover: Boolean,
    ) {
        val bg = if (hover) {
            val pulse = (sin(System.currentTimeMillis() / 400.0 * Math.PI) * 0.5 + 0.5).toFloat()
            val alpha = (0x60 + pulse * 0x30).toInt()
            (alpha shl 24) or 0x707070
        } else UiTheme.CARD_BG
        g.fill(x, y, x + w, y + cardH, bg)

        val thumbX = x + 2
        val thumbY = y + 2
        val thumbW = w - 4
        val thumb = Thumbnails.get(info.id)
        if (thumb != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, thumb, thumbX, thumbY, 0f, 0f, thumbW, thumbH, thumbW, thumbH)
        } else {
            g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0xFF000000.toInt())
        }

        if (info.isRecent(7)) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = f.width(tag) + 4
            val tagH = f.lineHeight + 2
            g.fill(thumbX + 2, thumbY + 2, thumbX + 2 + tw, thumbY + 2 + tagH, UiTheme.ACCENT_NEW_TAG)
            g.drawText(f, tag, thumbX + 4, thumbY + 3, UiTheme.TEXT_PRIMARY, false)
        }

        val dur = info.formatDuration()
        if (dur.isNotEmpty()) {
            val dw = f.width(dur) + 4
            val dh = f.lineHeight + 2
            val dx = thumbX + thumbW - dw - 2
            val dy = thumbY + thumbH - dh - 2
            g.fill(dx, dy, dx + dw, dy + dh, UiTheme.OVERLAY_SCRIM)
            g.drawText(f, dur, dx + 2, dy + 2, UiTheme.TEXT_PRIMARY, false)
        }

        if (compactCards) return

        val textX = x + 4
        val textW = w - 8
        var textY = thumbY + thumbH + 3
        for (line in UiText.wrap(f, info.title, textW, 2)) {
            g.drawText(f, line, textX, textY, UiTheme.TEXT_PRIMARY, false)
            textY += f.lineHeight + 1
        }

        var meta = info.uploader ?: ""
        val views = info.formatViews()
        if (views.isNotEmpty()) {
            meta = if (meta.isEmpty()) views
            else UiText.trim(f, meta, max(20, textW - f.width(" • $views"))) + " • " + views
        }
        if (meta.isNotEmpty()) {
            g.drawText(f, UiText.trim(f, meta, textW), textX, textY, UiTheme.TEXT_META, false)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        val stripTop = stripTop()
        val stripBottom = stripBottom()
        if (mouseY < stripTop || mouseY > stripBottom) return false
        val viewportW = stripRight() - stripLeft()
        val maxOff = maxScroll(viewportW, stripBottom - stripTop)
        val delta = if (vertical) dy * 32 else (if (dx != 0.0) dx else dy) * 32
        scrollOffset = (scrollOffset - delta.toInt()).coerceIn(0, maxOff)
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        if (clearButton.isMouseOver(mouseX, mouseY)) return clearButton.mouseClicked(event, dbl)
        if (searchButton.isMouseOver(mouseX, mouseY)) return searchButton.mouseClicked(event, dbl)
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            val handled = searchBox.mouseClicked(event, dbl)
            searchBox.isFocused = true
            return handled
        }
        searchBox.isFocused = false
        if (hoveredCard in controller.cards.indices) {
            val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            onPick(controller.cards[hoveredCard])
            return true
        }
        return super.mouseClicked(event, dbl)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchBox.isFocused) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                controller.runSearch(searchBox.value)
                return true
            }
            return searchBox.keyPressed(event)
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (searchBox.isFocused) return searchBox.charTyped(event)
        return super.charTyped(event)
    }

    companion object {
        private const val HEADER_H = 14
        private const val CARD_GAP = 6
        private const val CARD_W = 152
        private const val CARD_TEXT_H = 32
        private const val THUMB_H = 86
        private const val CARD_H = THUMB_H + CARD_TEXT_H
        private const val SEARCH_H = 22
        private const val ACTION_W = SEARCH_H
        private const val ACTION_GAP = 4
    }
}
