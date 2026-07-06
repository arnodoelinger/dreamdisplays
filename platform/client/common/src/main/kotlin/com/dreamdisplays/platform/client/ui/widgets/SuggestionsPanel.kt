package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiText
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.platform.client.ui.kit.darkenRgb
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.drawShimmer
import com.dreamdisplays.platform.client.ui.kit.fillVGradient
import com.dreamdisplays.platform.client.ui.kit.lightenRgb
import com.dreamdisplays.platform.client.ui.kit.renderChild
import com.dreamdisplays.api.media.search.MediaSearchResult
import com.dreamdisplays.platform.client.render.Thumbnails
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * Scrollable search / related-videos panel: a search row (edit box + clear + search buttons) above a
 * strip of video cards, laid out vertically (sidebar) or horizontally (bottom strip). All async
 * loading lives in [SuggestionsController]; this widget is the view.
 *
 * @param onPick invoked when the user clicks a result card.
 */
class SuggestionsPanel(
    private val onPick: (MediaSearchResult) -> Unit,
) : UiWidget(Component.translatable("dreamdisplays.button.suggestions")) {

    private val controller = SuggestionsController().also { it.onResults = { scrollOffset = 0 } }
    private val searchBox: EditBox
    private val clearButton: IconButton
    private val searchButton: IconButton

    /** When this returns false the panel is locked: it shows an "unavailable" notice and ignores input. */
    var available: () -> Boolean = { true }

    private var scrollOffset: Int = 0
    private var hoveredCard: Int = -1
    private var vertical: Boolean = false
    private var compactCards: Boolean = false
    private var lastStripH: Int = CARD_H

    override fun handlesWholeWidgetCursor(): Boolean = false

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

        if (!available()) {
            val notice = Component.translatable("dreamdisplays.suggestions.unavailable").string
            g.drawText(f, notice, x + 10, stripTop() + 6, UiTheme.TEXT_SECONDARY, false)
            return
        }

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
                if (cardThumbnail(info) == null) requestCardThumbnail(info)
            }
            pos += (if (vertical) ch else cw) + CARD_GAP
        }
        g.disableScissor()
        //? if >=1.21.11 {
        if (hoveredCard in cards.indices) g.requestCursor(CursorTypes.POINTING_HAND)
        //?}

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

    /**
     * The registered card thumbnail for [info]. Results carrying a [MediaSearchResult.thumbnailUrlOverride]
     * (e.g. Twitch) were registered under the raw id via the direct-URL path, so they're read from that
     * same slot ([Thumbnails.get] defaults to the raw-key HIGH tier); YouTube ids use the id-derived LOW tier.
     */
    private fun cardThumbnail(info: MediaSearchResult) =
        if (info.thumbnailUrlOverride != null) Thumbnails.get(info.id)
        else Thumbnails.get(info.id, Thumbnails.Quality.LOW)

    /** Requests [info]'s card thumbnail via the path matching [cardThumbnail]'s key, if not already loaded. */
    private fun requestCardThumbnail(info: MediaSearchResult) {
        val url = info.thumbnailUrlOverride
        if (url != null) Thumbnails.request(info.id, url)
        else Thumbnails.request(info.id, Thumbnails.Quality.LOW)
    }

    /** Draws one result card: hover-pulsing background, thumbnail, NEW/duration tags, title, and meta. */
    private fun drawCard(
        g: GuiGraphicsCompat, f: Font, info: MediaSearchResult,
        x: Int, y: Int, w: Int, thumbH: Int, cardH: Int, hover: Boolean,
    ) {
        // On hover, tint the card to the video's own dominant color (ambient), falling back to the
        // neutral grey highlight until the thumbnail (and thus its average color) has decoded.
        val ambient = if (hover) Thumbnails.averageColor(info.id) else null
        val hoverBg = ambient?.let { (0xC0 shl 24) or (darkenRgb(it, 0.45f) and 0x00FFFFFF) } ?: UiTheme.CARD_BG_HOVER
        val hoverBorder = ambient?.let { lightenRgb(it, 0.40f) } ?: UiTheme.CARD_BORDER_HOVER
        g.fill(x, y, x + w, y + cardH, if (hover) hoverBg else UiTheme.CARD_BG)

        // Full-bleed thumbnail across the whole card width (no side inset): it's the largest the
        // card can hold and the width/THUMB_H ratio matches 16:9, so the image stays crisp and
        // un-stretched instead of being shrunk into a bordered box.
        val thumbX = x
        val thumbY = y
        val thumbW = w
        val thumb = cardThumbnail(info)
        if (thumb != null) {
            blitTexture(g, thumb, thumbX, thumbY, thumbW, thumbH)
        } else {
            // Animated shimmer while the thumbnail is still downloading, instead of a dead black box.
            g.drawShimmer(
                thumbX, thumbY, thumbX + thumbW, thumbY + thumbH,
                UiTheme.PLACEHOLDER_BG, UiTheme.PLACEHOLDER_SHIMMER,
            )
        }

        if (info.isRecent(7)) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = f.width(tag) + 6
            val tagH = f.lineHeight + 4
            g.fill(thumbX + 2, thumbY + 2, thumbX + 2 + tw, thumbY + 2 + tagH, UiTheme.ACCENT_NEW_TAG)
            g.drawText(f, tag, thumbX + 5, thumbY + 4, UiTheme.TEXT_PRIMARY, true)
        }

        val dur = info.formatDuration()
        if (dur.isNotEmpty()) {
            // Soft bottom-up gradient across the thumbnail (YouTube-style) instead of a hard black
            // box, so the duration reads on any frame without an ugly rectangle.
            val scrimH = (thumbH * 2) / 5
            g.fillVGradient(
                thumbX, thumbY + thumbH - scrimH, thumbX + thumbW, thumbY + thumbH,
                0x00000000, 0xB0000000.toInt(),
            )
            val dx = thumbX + thumbW - f.width(dur) - 4
            val dy = thumbY + thumbH - f.lineHeight - 3
            g.drawText(f, dur, dx, dy, UiTheme.TEXT_PRIMARY, true)
        }

        // Draw the hover frame over the full card (including the full-bleed thumbnail's edges) so the
        // selection reads as one crisp outline instead of an animated flicker.
        if (hover) g.drawOutline(UiRect(x, y, w, cardH), hoverBorder)

        if (compactCards) return

        val textX = x + 4
        val textW = w - 8
        var textY = thumbY + thumbH + 4
        for (line in UiText.wrap(f, info.title, textW, 2)) {
            g.drawText(f, line, textX, textY, UiTheme.TEXT_PRIMARY, true)
            textY += f.lineHeight + 1
        }

        textY += 1
        var meta = info.uploader ?: ""
        val views = info.formatViews()
        if (views.isNotEmpty()) {
            meta = if (meta.isEmpty()) views
            else UiText.trim(f, meta, max(20, textW - f.width(" • $views"))) + " • " + views
        }
        if (meta.isNotEmpty()) {
            g.drawText(f, UiText.trim(f, meta, textW), textX, textY, UiTheme.TEXT_META, true)
        }
    }

    private fun blitTexture(g: GuiGraphicsCompat, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        //? if >=1.21.11 {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, w, h)
        //?} else
        /*g.blit(id, x, y, 0f, 0f, w, h, w, h)*/
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        if (!available()) return false
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

    //? if >=1.21.11 {
    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        if (!available()) return false
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
        val card = if (event.button() == 0) cardAt(mouseX, mouseY) else -1
        if (card in controller.cards.indices) {
            val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            onPick(controller.cards[card])
            return true
        }
        return false
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!available()) return false
        if (clearButton.isMouseOver(mouseX, mouseY)) return clearButton.mouseClicked(mouseX, mouseY, button)
        if (searchButton.isMouseOver(mouseX, mouseY)) return searchButton.mouseClicked(mouseX, mouseY, button)
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            val handled = searchBox.mouseClicked(mouseX, mouseY, button)
            searchBox.isFocused = true
            return handled
        }
        searchBox.isFocused = false
        val card = if (button == 0) cardAt(mouseX, mouseY) else -1
        if (card in controller.cards.indices) {
            val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            onPick(controller.cards[card])
            return true
        }
        return false
    }*/

    private fun cardAt(mouseX: Double, mouseY: Double): Int {
        if (controller.statusKey != null) return -1
        val stripTop = stripTop()
        val stripBottom = stripBottom()
        val stripLeft = stripLeft()
        val stripRight = stripRight()
        if (mouseX < stripLeft || mouseX >= stripRight || mouseY < stripTop || mouseY >= stripBottom) return -1

        val viewportW = stripRight - stripLeft
        val viewportH = stripBottom - stripTop
        if (viewportW <= 0 || viewportH <= 0) return -1

        val cw = cardW(viewportW)
        val ch = cardH(viewportW)
        val rowY = if (vertical) 0 else stripTop + max(0, (viewportH - ch) / 2)
        var pos = (if (vertical) stripTop else stripLeft) - scrollOffset
        for (i in controller.cards.indices) {
            val cardX = if (vertical) stripLeft else pos
            val cardY = if (vertical) pos else rowY
            if (mouseX >= max(cardX, stripLeft) && mouseX < min(cardX + cw, stripRight) &&
                mouseY >= max(cardY, stripTop) && mouseY < min(cardY + ch, stripBottom)
            ) {
                return i
            }
            pos += (if (vertical) ch else cw) + CARD_GAP
        }
        return -1
    }

    //? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        if (!available()) return super.keyPressed(event)
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
    //?} else
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!available()) return super.keyPressed(keyCode, scanCode, modifiers)
        if (searchBox.isFocused) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                controller.runSearch(searchBox.value)
                return true
            }
            return searchBox.keyPressed(keyCode, scanCode, modifiers)
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(chr: Char, modifiers: Int): Boolean {
        if (searchBox.isFocused) return searchBox.charTyped(chr, modifiers)
        return super.charTyped(chr, modifiers)
    }*/

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

        /** Internal vertical paddings a card adds around its thumbnail + text (see [dynThumbH]/[dynCardH]). */
        private const val CARD_INNER_PAD = 2 + 3 + 2

        /**
         * Vertical space the panel spends on its title + search row before the card strip begins,
         * plus the bottom padding below it. Keep in sync with [stripTop]/[stripBottom].
         */
        const val STRIP_CHROME_H = 10 + HEADER_H + 6 + SEARCH_H + 8 + 10

        /** Strip viewport height at which horizontal cards reach their full [THUMB_H] thumbnails. */
        const val FULL_CARD_VIEWPORT_H = CARD_H + CARD_INNER_PAD

        /** Smallest strip viewport that still shows a card (min 30px thumbnail) without clipping it. */
        const val MIN_CARD_VIEWPORT_H = 30 + CARD_TEXT_H + CARD_INNER_PAD
    }
}
