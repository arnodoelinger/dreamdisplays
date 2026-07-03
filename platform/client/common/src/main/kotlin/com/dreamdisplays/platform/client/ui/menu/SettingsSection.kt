package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.platform.client.ui.widgets.IconButton
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

/**
 * The settings panel of the display menu: labeled rows (volume, render distance, quality,
 * brightness, sync), each with its control widget and reset button, plus the owner action buttons
 * (lock/delete/report) anchored to the panel's bottom-right corner. Tooltips are declared per row
 * and rendered after the widgets so they overlay everything.
 */
class SettingsSection(
    private val rows: List<Row>,
    private val ownerActions: List<IconButton?>,
    private val buttonTooltips: List<Pair<IconButton?, () -> List<Component>?>>,
) {
    /**
     * One settings row: a translated label on the left, the control and its reset button on the
     * right, and a tooltip shown when the label is hovered.
     *
     * @param extraGapBefore additional vertical gap above this row (the sync row is set apart).
     */
    class Row(
        val labelKey: String,
        val control: UiWidget,
        val reset: IconButton,
        val extraGapBefore: Int = 0,
        val tooltip: () -> List<Component>,
    ) {
        internal var labelHover: UiRect? = null
    }

    /** Draws all rows into [panel] and places the owner action buttons in its bottom-right corner. */
    fun render(g: GuiGraphicsCompat, panel: UiRect) {
        val font = Minecraft.getInstance().font
        val innerX = panel.x + UiTheme.PANEL_PADDING_X
        val innerW = panel.w - UiTheme.PANEL_PADDING_X * 2
        var rowY = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 6

        // Reserve a shared label column as wide as the widest label so every control gets the same
        // width and left edge, instead of each being squeezed by its own label length (which made
        // the sliders visibly different widths from row to row).
        val labelColW = rows.maxOf { font.width(Component.translatable(it.labelKey)) }

        for (row in rows) {
            rowY += row.extraGapBefore
            renderRow(g, row, innerX, rowY, innerW, labelColW)
            rowY += UiTheme.ROW_H + UiTheme.ROW_GAP
        }

        placeOwnerActions(panel)
    }

    /** Draws one row's background and label, and places its control and reset button. */
    private fun renderRow(g: GuiGraphicsCompat, row: Row, x: Int, y: Int, w: Int, labelColW: Int) {
        val font = Minecraft.getInstance().font
        g.fill(x, y, x + w, y + UiTheme.ROW_H, UiTheme.ROW_BG)
        val label = Component.translatable(row.labelKey)
        val textY = y + UiTheme.ROW_H / 2 - font.lineHeight / 2
        g.drawText(font, label, x + 6, textY, UiTheme.TEXT_PRIMARY, false)
        row.labelHover = UiRect(x + 6, textY, font.width(label), font.lineHeight)

        // Right-align the reset button to the panel's inner edge (x + w), matching the owner action
        // buttons below; the old `- 4` inset left it 4px shy of them, looking misaligned.
        var rightEdge = x + w
        row.reset.place(UiRect(rightEdge - UiTheme.RESET_W, y, UiTheme.RESET_W, UiTheme.ROW_H))
        rightEdge -= UiTheme.RESET_W + 4

        // Size every control from the shared label column, so all rows get an identical control width.
        val controlW = min(UiTheme.CONTROL_W, max(60, rightEdge - (x + 6 + labelColW + 8)))
        row.control.place(UiRect(rightEdge - controlW, y, controlW, UiTheme.ROW_H))
    }

    /** Places the owner action buttons right-to-left along the panel's bottom-right corner. */
    private fun placeOwnerActions(panel: UiRect) {
        val btn = UiTheme.CONTROL_BUTTON
        var rightEdge = panel.right - UiTheme.PANEL_PADDING_X
        val yEdge = panel.bottom - UiTheme.PANEL_PADDING_X - btn
        for (b in ownerActions) {
            if (b == null || !b.visible) continue
            b.place(UiRect(rightEdge - btn, yEdge, btn, btn))
            rightEdge -= btn + 4
        }
    }

    /**
     * Renders the tooltip of whichever row label or action button is hovered. Hit-testing uses the
     * virtual ([mouseX], [mouseY]) coordinates; the tooltip is anchored at the real ([anchorX],
     * [anchorY]) coordinates because deferred tooltips render unscaled, outside the menu's transform.
     */
    fun renderTooltips(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, anchorX: Int, anchorY: Int) {
        val font = Minecraft.getInstance().font
        for (row in rows) {
            if (row.labelHover?.contains(mouseX, mouseY) == true) {
                renderTooltip(g, row.tooltip(), anchorX, anchorY)
            }
        }
        for ((button, tooltip) in buttonTooltips) {
            if (button == null || !button.visible) continue
            if (mouseX >= button.x && mouseX < button.x + button.width &&
                mouseY >= button.y && mouseY < button.y + button.height
            ) {
                tooltip()?.let { renderTooltip(g, it, anchorX, anchorY) }
            }
        }
    }

    private fun renderTooltip(g: GuiGraphicsCompat, lines: List<Component>, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        //? if >=1.21.11 {
        g.setComponentTooltipForNextFrame(font, lines, x, y)
        //?} else
        /*g.renderComponentTooltip(font, lines, x, y)*/
    }
}
