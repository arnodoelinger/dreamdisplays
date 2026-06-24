package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import net.minecraft.client.gui.Font
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.components.AbstractWidget

/**
 * Version-neutral drawing helpers built on [GuiGraphicsCompat]: bordered panels, 1px outlines, and
 * rendering vanilla child widgets from inside a composite widget. The per-version call names are
 * gated here once so screen and widget code stays branch-free.
 */

/** Draws a 1px [color] outline just inside [r]. */
fun GuiGraphicsCompat.drawOutline(r: UiRect, color: Int) {
    fill(r.x, r.y, r.right, r.y + 1, color)
    fill(r.x, r.bottom - 1, r.right, r.bottom, color)
    fill(r.x, r.y, r.x + 1, r.bottom, color)
    fill(r.right - 1, r.y, r.right, r.bottom, color)
}

/** Fills [r] with [bg], outlines it with [border], and draws [title] in the top-left padding corner. */
fun GuiGraphicsCompat.drawPanel(
    font: Font, r: UiRect, title: String,
    bg: Int = UiTheme.PANEL_BG, border: Int = UiTheme.PANEL_BORDER,
) {
    fill(r.x, r.y, r.right, r.bottom, bg)
    drawOutline(r, border)
    drawText(font, title, r.x + UiTheme.PANEL_PADDING_X, r.y + UiTheme.PANEL_PADDING_Y, UiTheme.TEXT_PRIMARY, false)
}

/**
 * Renders a vanilla child widget (e.g. an [net.minecraft.client.gui.components.EditBox] nested inside a
 * composite) through the per-version render entry point: `extractRenderState` on 26+, `render` pre-26.
 */
//? if >=26 {
fun AbstractWidget.renderChild(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
    extractRenderState(g, mouseX, mouseY, partialTick)
//?} else
/*fun AbstractWidget.renderChild(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) =
    render(g, mouseX, mouseY, partialTick)*/
