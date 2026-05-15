package com.dreamdisplays.client.ui.widgets

import com.dreamdisplays.Initializer
import com.dreamdisplays.ytdlp.Thumbnails
import com.dreamdisplays.ytdlp.YouTubeWeb
import com.dreamdisplays.ytdlp.YtDlp
import com.dreamdisplays.ytdlp.YtVideoInfo
import me.inotsleep.utils.logging.LoggingManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Suggestions panel widget. **/
class SuggestionsPanelWidget(
    x: Int, y: Int, width: Int, height: Int,
    private val onPick: Consumer<YtVideoInfo>,
) : AbstractWidget(x, y, width, height, Component.translatable("dreamdisplays.button.suggestions")) {

    private val searchBox: EditBox
    private val clearButtonWidget: ButtonWidget
    private val searchActionButtonWidget: ButtonWidget
    private val cards = ArrayList<YtVideoInfo>()
    private val requestSeq = AtomicInteger()
    private var currentVideoId: String? = null
    private var statusMessage: String? = null
    private var loadStartedAtMs: Long = 0L
    private var scrollOffset: Int = 0
    private var hoveredCard: Int = -1
    private var vertical: Boolean = false
    private var compactCards: Boolean = false
    private var verticalCardW: Int = CARD_W
    private var lastStripH: Int = CARD_H

    init {
        val f = Minecraft.getInstance().font
        searchBox = EditBox(f, x + 10, y + searchY(),
            searchBoxWidth(width), SEARCH_H,
            Component.translatable("dreamdisplays.suggestions.search"))
        searchBox.setHint(Component.translatable("dreamdisplays.suggestions.search"))
        searchBox.setMaxLength(200)
        clearButtonWidget = object : ButtonWidget(0, 0, ACTION_W, SEARCH_H, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "cross"), 4) {
            override fun onPress() {
                searchBox.value = ""
                searchBox.isFocused = true
            }
        }
        searchActionButtonWidget = object : ButtonWidget(0, 0, ACTION_W, SEARCH_H, 64, 64,
            Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "search"), 4) {
            override fun onPress() { runSearch() }
        }
    }

    fun setVertical(v: Boolean) { vertical = v }
    fun setCompactCards(c: Boolean) { compactCards = c }

    private fun searchY(): Int = 10 + HEADER_H + 6
    private fun searchBoxWidth(panelW: Int): Int = panelW - 20 - (ACTION_W + ACTION_GAP) * 2
    private fun clearButtonX(): Int = x + width - 10 - ACTION_W * 2 - ACTION_GAP
    private fun searchButtonX(): Int = x + width - 10 - ACTION_W
    private fun actionRowY(): Int = searchBox.y

    fun setRelatedTo(videoId: String?) {
        if (videoId.isNullOrEmpty()) {
            currentVideoId = null
            cards.clear()
            statusMessage = null
            return
        }
        if (videoId == currentVideoId && cards.isNotEmpty()) return
        currentVideoId = videoId
        loadRelated(videoId)
    }

    fun runSearch() {
        val q = searchBox.value.trim()
        if (q.isEmpty()) {
            currentVideoId?.let { loadRelated(it) }
            return
        }
        val maybeId = if (q.startsWith("http") || "youtube.com" in q || "youtu.be" in q)
            YtDlp.extractVideoId(q) else null
        if (maybeId != null) {
            startLoad()
            val seq2 = requestSeq.incrementAndGet()
            EXECUTOR.submit {
                try {
                    val meta = YouTubeWeb.metadata(maybeId)
                    if (meta != null) publish(seq2, listOf(meta), null)
                    else publish(seq2, listOf(YtVideoInfo(maybeId,
                        "https://youtu.be/$maybeId", null, null, null)), null)
                } catch (e: Exception) {
                    LoggingManager.warn("[Suggestions] URL meta fetch failed: ${e.message}")
                    publish(seq2, listOf(YtVideoInfo(maybeId,
                        "https://youtu.be/$maybeId", null, null, null)), null)
                }
            }
            return
        }
        startLoad()
        val seq = requestSeq.incrementAndGet()
        EXECUTOR.submit {
            try {
                val r = YtDlp.search(q, RESULT_LIMIT)
                publish(seq, r, null)
            } catch (e: Exception) {
                LoggingManager.warn("[Suggestions] search failed '$q': ${e.message}")
                publish(seq, null, "dreamdisplays.suggestions.error")
            }
        }
    }

    private fun loadRelated(videoId: String) {
        startLoad()
        val seq = requestSeq.incrementAndGet()
        EXECUTOR.submit {
            try {
                val r = YtDlp.related(videoId, RESULT_LIMIT)
                publish(seq, r, null)
            } catch (e: Exception) {
                LoggingManager.warn("[Suggestions] related failed $videoId: ${e.message}")
                publish(seq, null, "dreamdisplays.suggestions.error")
            }
        }
    }

    private fun startLoad() {
        statusMessage = "dreamdisplays.suggestions.loading"
        loadStartedAtMs = System.currentTimeMillis()
        cards.clear()
        scrollOffset = 0
    }

    private fun publish(seq: Int, results: List<YtVideoInfo>?, error: String?) {
        Minecraft.getInstance().execute {
            if (seq != requestSeq.get()) return@execute
            cards.clear()
            scrollOffset = 0
            if (error != null) {
                statusMessage = error
                return@execute
            }
            if (results.isNullOrEmpty()) {
                statusMessage = "dreamdisplays.suggestions.empty"
                return@execute
            }
            statusMessage = null
            cards.addAll(results.subList(0, min(results.size, RESULT_LIMIT)))
            for (info in cards) Thumbnails.request(info.id, info.getThumbnailUrl())
        }
    }

    override fun setX(x: Int) {
        super.setX(x)
        searchBox.x = x + 10
    }

    override fun setY(y: Int) {
        super.setY(y)
        searchBox.y = y + searchY()
    }

    override fun setWidth(w: Int) {
        super.setWidth(w)
        searchBox.width = searchBoxWidth(w)
    }

    override fun renderWidget(g: GuiGraphics, mouseX: Int, mouseY: Int, dt: Float) {
        g.fill(x, y, x + width, y + height, PANEL_BG)
        g.fill(x, y, x + width, y + 1, PANEL_BORDER)
        g.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER)
        g.fill(x, y, x + 1, y + height, PANEL_BORDER)
        g.fill(x + width - 1, y, x + width, y + height, PANEL_BORDER)

        val f = Minecraft.getInstance().font
        g.drawString(f, message, x + 10, y + 10, 0xFFFFFFFF.toInt(), false)
        searchBox.render(g, mouseX, mouseY, dt)

        clearButtonWidget.x = clearButtonX()
        clearButtonWidget.y = actionRowY()
        clearButtonWidget.render(g, mouseX, mouseY, dt)

        searchActionButtonWidget.x = searchButtonX()
        searchActionButtonWidget.y = actionRowY()
        searchActionButtonWidget.render(g, mouseX, mouseY, dt)

        val stripTop = searchBox.y + SEARCH_H + 8
        val stripBottom = y + height - 10
        val stripH = stripBottom - stripTop
        if (stripH < 40) return

        if (statusMessage != null) {
            val base = Component.translatable(statusMessage!!).string
            val msg = if (statusMessage == "dreamdisplays.suggestions.loading") {
                val elapsed = maxOf(0L, (System.currentTimeMillis() - loadStartedAtMs) / 1000L)
                base.replace(Regex("\\.+$"), "") + " • " + elapsed + "s"
            } else base
            g.drawString(f, msg, x + 10, stripTop + 6, 0xFFAAAAAA.toInt(), false)
            return
        }

        val stripLeft = x + 10
        val stripRight = x + width - 10
        val viewportW = stripRight - stripLeft
        lastStripH = stripH

        if (vertical) {
            verticalCardW = max(CARD_W, viewportW)
            var vCardH = if (compactCards) (THUMB_H + 4) else CARD_H
            val vThumbH = max(THUMB_H, (verticalCardW * 180.0 / 320.0).toInt())
            if (!compactCards) vCardH = vThumbH + CARD_TEXT_H

            val contentH = cards.size * (vCardH + CARD_GAP) - CARD_GAP
            val viewportH = stripBottom - stripTop
            val maxOff = max(0, contentH - viewportH)
            scrollOffset = max(0, min(maxOff, scrollOffset))

            g.enableScissor(stripLeft, stripTop, stripRight, stripBottom)
            hoveredCard = -1
            var cy = stripTop - scrollOffset
            for (i in cards.indices) {
                val info = cards[i]
                val cardTop = cy
                val cardBottom = cy + vCardH
                if (cardBottom >= stripTop && cardTop <= stripBottom) {
                    val hover = mouseX >= stripLeft && mouseX < stripLeft + verticalCardW
                            && mouseY >= cardTop && mouseY < cardBottom
                            && mouseY >= stripTop && mouseY < stripBottom
                    if (hover) hoveredCard = i
                    renderCardSized(g, f, info, stripLeft, cardTop, verticalCardW, vThumbH, vCardH, hover)
                    if (Thumbnails.get(info.id) == null)
                        Thumbnails.request(info.id, info.getThumbnailUrl())
                }
                cy += vCardH + CARD_GAP
            }
            g.disableScissor()

            if (contentH > viewportH) {
                val barX = stripRight + 1
                g.fill(barX, stripTop, barX + 2, stripBottom, 0xFF202020.toInt())
                val barH = max(20, (viewportH.toFloat() / contentH * viewportH).toInt())
                val barY = stripTop + (scrollOffset.toFloat() / maxOff * (viewportH - barH)).toInt()
                g.fill(barX, barY, barX + 2, barY + barH, 0xFF808080.toInt())
            }
            return
        }

        val hCardH = dynCardH()
        val hThumbH = dynThumbH()
        val hCardW = dynCardW()
        val rowY = stripTop + max(0, (stripBottom - stripTop - hCardH) / 2)

        val contentW = cards.size * (hCardW + CARD_GAP) - CARD_GAP
        val maxOff = max(0, contentW - viewportW)
        scrollOffset = max(0, min(maxOff, scrollOffset))

        g.enableScissor(stripLeft, stripTop, stripRight, stripBottom)
        hoveredCard = -1
        var cx = stripLeft - scrollOffset
        for (i in cards.indices) {
            val info = cards[i]
            val cardLeft = cx
            val cardRight = cx + hCardW
            if (cardRight >= stripLeft && cardLeft <= stripRight) {
                val hover = mouseX in cardLeft..<cardRight
                        && mouseY >= rowY && mouseY < rowY + hCardH
                        && mouseX >= stripLeft && mouseX < stripRight
                if (hover) hoveredCard = i
                renderCardSized(g, f, info, cardLeft, rowY, hCardW, hThumbH, hCardH, hover)
                if (Thumbnails.get(info.id) == null)
                    Thumbnails.request(info.id, info.getThumbnailUrl())
            }
            cx += hCardW + CARD_GAP
        }
        g.disableScissor()

        if (contentW > viewportW) {
            val barY = stripBottom + 1
            g.fill(stripLeft, barY, stripRight, barY + 2, 0xFF202020.toInt())
            val barW = max(20, (viewportW.toFloat() / contentW * viewportW).toInt())
            val barX = stripLeft + (scrollOffset.toFloat() / maxOff * (viewportW - barW)).toInt()
            g.fill(barX, barY, barX + barW, barY + 2, 0xFF808080.toInt())
        }
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

    private fun renderCardSized(g: GuiGraphics, f: Font, info: YtVideoInfo, x: Int, y: Int,
                                w: Int, thumbH: Int, cardH: Int, hover: Boolean) {
        val bg = if (hover) {
            val pulse = (sin(System.currentTimeMillis() / 400.0 * Math.PI) * 0.5 + 0.5).toFloat()
            val alpha = (0x60 + pulse * 0x30).toInt()
            (alpha shl 24) or 0x707070
        } else CARD_BG
        g.fill(x, y, x + w, y + cardH, bg)

        val thumbX = x + 2
        val thumbY = y + 2
        val thumbW = w - 4
        val thumb = Thumbnails.get(info.id)
        if (thumb != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, thumb,
                thumbX, thumbY, 0f, 0f, thumbW, thumbH, thumbW, thumbH)
        } else {
            g.fill(thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0xFF000000.toInt())
        }

        if (info.isRecent(7)) {
            val tag = Component.translatable("dreamdisplays.ui.new").string
            val tw = f.width(tag) + 4
            val th = f.lineHeight + 2
            g.fill(thumbX + 2, thumbY + 2, thumbX + 2 + tw, thumbY + 2 + th, 0xFFE53935.toInt())
            g.drawString(f, tag, thumbX + 4, thumbY + 3, 0xFFFFFFFF.toInt(), false)
        }

        val dur = info.formatDuration()
        if (dur.isNotEmpty()) {
            val dw = f.width(dur) + 4
            val dh = f.lineHeight + 2
            val dx = thumbX + thumbW - dw - 2
            val dy = thumbY + thumbH - dh - 2
            g.fill(dx, dy, dx + dw, dy + dh, 0xC0000000.toInt())
            g.drawString(f, dur, dx + 2, dy + 2, 0xFFFFFFFF.toInt(), false)
        }

        if (compactCards) return

        val textX = x + 4
        val textW = w - 8
        var textY = thumbY + thumbH + 3
        val titleLines = wrap(f, info.title, textW, 2)
        for (line in titleLines) {
            g.drawString(f, line, textX, textY, 0xFFFFFFFF.toInt(), false)
            textY += f.lineHeight + 1
        }

        var meta = info.uploader ?: ""
        val views = info.formatViews()
        if (views.isNotEmpty()) {
            meta = if (meta.isEmpty()) views
            else trim(f, meta, max(20, textW - f.width(" • $views"))) + " • " + views
        }
        if (meta.isNotEmpty()) {
            g.drawString(f, trim(f, meta, textW), textX, textY, 0xFFB8B8B8.toInt(), false)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        val stripTop = searchBox.y + SEARCH_H + 8
        val stripBottom = y + height - 10
        if (mouseY < stripTop || mouseY > stripBottom) return false
        val maxOff: Int = if (vertical) {
            val viewportH = stripBottom - stripTop
            val viewportW2 = width - 20
            val vCardW = max(CARD_W, viewportW2)
            val vThumbH2 = max(THUMB_H, (vCardW * 180.0 / 320.0).toInt())
            val vCardH2 = if (compactCards) (THUMB_H + 4) else (vThumbH2 + CARD_TEXT_H)
            val contentH = cards.size * (vCardH2 + CARD_GAP) - CARD_GAP
            max(0, contentH - viewportH)
        } else {
            val viewportW = width - 20
            val contentW = cards.size * (dynCardW() + CARD_GAP) - CARD_GAP
            max(0, contentW - viewportW)
        }
        val delta = if (vertical) dy * 32 else (if (dx != 0.0) dx else dy) * 32
        scrollOffset = max(0, min(maxOff, scrollOffset - delta.toInt()))
        return true
    }

    override fun mouseClicked(event: MouseButtonEvent, dbl: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        if (clearButtonWidget.isMouseOver(mouseX, mouseY)) return clearButtonWidget.mouseClicked(event, dbl)
        if (searchActionButtonWidget.isMouseOver(mouseX, mouseY)) return searchActionButtonWidget.mouseClicked(event, dbl)
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            val handled = searchBox.mouseClicked(event, dbl)
            searchBox.isFocused = true
            return handled
        }
        searchBox.isFocused = false
        if (hoveredCard in cards.indices) {
            val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
            Minecraft.getInstance().soundManager.play(s)
            onPick.accept(cards[hoveredCard])
            return true
        }
        return super.mouseClicked(event, dbl)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (searchBox.isFocused) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                runSearch()
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

    override fun updateWidgetNarration(out: NarrationElementOutput) {}

    companion object {
        private const val RESULT_LIMIT = 12
        private const val HEADER_H = 14
        private const val CARD_GAP = 6
        private const val CARD_W = 152
        private const val CARD_TEXT_H = 32
        private const val THUMB_H = 86
        private const val CARD_H = THUMB_H + CARD_TEXT_H
        private const val PANEL_BG = 0x9F0F0F0F.toInt()
        private const val PANEL_BORDER = 0xFF7A7A7A.toInt()
        private const val CARD_BG = 0x602A2A2A
        private const val SEARCH_H = 22
        private const val ACTION_W = SEARCH_H
        private const val ACTION_GAP = 4

        private val EXECUTOR = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "DD-Suggestions").apply { isDaemon = true }
        }

        private fun wrap(f: Font, s: String, maxW: Int, maxLines: Int): List<String> {
            val out = ArrayList<String>()
            val words = s.split(Regex("\\s+"))
            val cur = StringBuilder()
            for (word in words) {
                val trial = if (cur.isEmpty()) word else "$cur $word"
                if (f.width(trial) <= maxW) {
                    cur.setLength(0); cur.append(trial)
                } else {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        if (out.size == maxLines) break
                        cur.setLength(0)
                    }
                    if (f.width(word) > maxW) {
                        out.add(trim(f, word, maxW))
                        if (out.size == maxLines) break
                    } else {
                        cur.append(word)
                    }
                }
            }
            if (cur.isNotEmpty() && out.size < maxLines) out.add(cur.toString())
            if (out.isEmpty()) out.add("")
            return out
        }

        private fun trim(f: Font, s: String, maxW: Int): String {
            if (f.width(s) <= maxW) return s
            val dots = "..."
            val dotsW = f.width(dots)
            val sb = StringBuilder()
            for (c in s.toCharArray()) {
                if (f.width(sb.toString() + c) + dotsW > maxW) break
                sb.append(c)
            }
            return sb.toString() + dots
        }
    }
}
