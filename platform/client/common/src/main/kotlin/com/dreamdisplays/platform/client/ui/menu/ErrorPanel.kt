package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import com.dreamdisplays.media.DreamMediaException
import com.dreamdisplays.media.MediaFailureKind
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.min

/**
 * The centered "video failed to load" panel shown instead of the normal menu content. It now states
 * what actually went wrong — a per-kind headline plus the underlying failure message — and offers
 * Retry / Report / Delete. Retry re-resolves the same URL in place, so a transient failure no longer
 * forces the player to delete a display they want to keep.
 */
class ErrorPanel(
    private val retryButton: IconButton,
    private val deleteButton: IconButton,
    private val reportButton: IconButton?,
    private val error: () -> DreamMediaException?,
) {
    /** Draws the error panel centered in a [screenW] x [screenH] screen and places the action buttons. */
    fun render(g: GuiGraphicsCompat, screenW: Int, screenH: Int) {
        val font = Minecraft.getInstance().font
        val lh = font.lineHeight
        val panelW = min(420, screenW - 40)
        val textW = panelW - 2 * UiTheme.PANEL_PADDING_X

        val ex = error()
        val headline = Component.translatable(headlineKey(ex?.kind)).withStyle { it.withColor(ChatFormatting.RED) }
        val detailLines = detailLines(font, ex?.message, textW)
        val hint = Component.translatable("dreamdisplays.error.loadingerror.hint").withStyle { it.withColor(ChatFormatting.GRAY) }
        val headerH = UiTheme.PANEL_PADDING_Y + lh + 6
        val detailH = if (detailLines.isEmpty()) 0 else detailLines.size * (lh + 2) + 8
        val contentH = 8 + lh + 8 + detailH + lh + 12 + BUTTON_SIZE
        val panelH = headerH + contentH + UiTheme.PANEL_PADDING_Y
        val panel = UiRect(screenW / 2 - panelW / 2, screenH / 2 - panelH / 2, panelW, panelH)
        g.drawPanel(font, panel, Component.translatable("dreamdisplays.ui.error").string)

        var y = panel.y + headerH + 8
        g.drawText(font, headline, screenW / 2 - font.width(headline) / 2, y, UiTheme.TEXT_PRIMARY, false)
        y += lh + 8

        for (line in detailLines) {
            g.drawText(font, line, screenW / 2 - font.width(line) / 2, y, UiTheme.TEXT_SECONDARY, false)
            y += lh + 2
        }
        if (detailLines.isNotEmpty()) y += 8

        g.drawText(font, hint, screenW / 2 - font.width(hint) / 2, y, UiTheme.TEXT_PRIMARY, false)

        // Action row centered along the bottom edge: Retry, Report (when enabled), Delete.
        val count = 2 + (if (reportButton != null) 1 else 0)
        val rowW = count * BUTTON_SIZE + (count - 1) * BUTTON_GAP
        val by = panel.bottom - UiTheme.PANEL_PADDING_Y - BUTTON_SIZE
        var bx = panel.centerX - rowW / 2
        retryButton.place(UiRect(bx, by, BUTTON_SIZE, BUTTON_SIZE))
        bx += BUTTON_SIZE + BUTTON_GAP
        if (reportButton != null) {
            reportButton.place(UiRect(bx, by, BUTTON_SIZE, BUTTON_SIZE))
            bx += BUTTON_SIZE + BUTTON_GAP
        }
        deleteButton.place(UiRect(bx, by, BUTTON_SIZE, BUTTON_SIZE))
    }

    /** Word-wraps the raw failure [message] to [maxW], capped at [MAX_DETAIL_LINES] (the last gets an ellipsis). */
    private fun detailLines(font: Font, message: String?, maxW: Int): List<String> {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && font.width(candidate) > maxW) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        if (lines.size <= MAX_DETAIL_LINES) return lines
        return lines.take(MAX_DETAIL_LINES).toMutableList().also { it[it.lastIndex] = it.last() + " …" }
    }

    companion object {
        private const val BUTTON_SIZE = 20
        private const val BUTTON_GAP = 6
        private const val MAX_DETAIL_LINES = 3

        /** Maps a failure [kind] to its short headline translation key. */
        private fun headlineKey(kind: MediaFailureKind?): String = when (kind) {
            MediaFailureKind.NETWORK -> "dreamdisplays.error.kind.network"
            MediaFailureKind.DECODE -> "dreamdisplays.error.kind.decode"
            MediaFailureKind.NOT_FOUND -> "dreamdisplays.error.kind.notfound"
            MediaFailureKind.GEO_BLOCKED -> "dreamdisplays.error.kind.geoblocked"
            MediaFailureKind.TIMEOUT -> "dreamdisplays.error.kind.timeout"
            else -> "dreamdisplays.error.kind.unknown"
        }
    }
}
