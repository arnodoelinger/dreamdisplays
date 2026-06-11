package com.dreamdisplays.client.ui.menu

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.drawText
import com.dreamdisplays.client.ui.kit.UiRect
import net.minecraft.client.Minecraft

/**
 * The small two-item popup ("Window" / "In-game") that opens above the popout button. Owns its
 * visibility, hit-testing, and drawing; the menu only toggles it and forwards clicks.
 *
 * @param onWindow invoked when the user picks the GLFW window mode.
 * @param onPip invoked when the user picks the in-game PiP mode.
 *
 * @since 1.7.0
 */
class PopoutDropdown(
    private val onWindow: () -> Unit,
    private val onPip: () -> Unit,
) {
    var visible: Boolean = false
    private var rect = UiRect(0, 0, WIDTH, ITEM_H * 2)

    /** Toggles dropdown visibility (popout button behavior when no popout is active). */
    fun toggle() {
        visible = !visible
    }

    /** Hides the dropdown. */
    fun hide() {
        visible = false
    }

    /** Draws the dropdown anchored above the rect at ([anchorX], [anchorY]) when visible. */
    fun draw(g: GuiGraphicsCompat, anchorX: Int, anchorY: Int) {
        if (!visible) return
        rect = UiRect(anchorX, anchorY - ITEM_H * 2 - 2, WIDTH, ITEM_H * 2)
        val (x, y) = rect.x to rect.y
        g.fill(x, y, x + WIDTH, y + ITEM_H * 2, 0xFF1C1C1C.toInt())
        g.fill(x, y, x + WIDTH, y + 1, 0xFF555555.toInt())
        g.fill(x, y + ITEM_H, x + WIDTH, y + ITEM_H + 1, 0xFF333333.toInt())
        g.fill(x, y + ITEM_H * 2 - 1, x + WIDTH, y + ITEM_H * 2, 0xFF555555.toInt())
        g.fill(x, y, x + 1, y + ITEM_H * 2, 0xFF555555.toInt())
        g.fill(x + WIDTH - 1, y, x + WIDTH, y + ITEM_H * 2, 0xFF555555.toInt())
        val font = Minecraft.getInstance().font
        val fy = y + (ITEM_H - font.lineHeight) / 2
        g.drawText(font, "Window", x + 6, fy, 0xFFFFFFFF.toInt(), false)
        g.drawText(font, "In-game", x + 6, fy + ITEM_H, 0xFFDDDDDD.toInt(), false)
    }

    /**
     * Handles a left click while visible: picks an item if the click is inside, always hides.
     * Returns true if an item was picked (the click is consumed).
     */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        visible = false
        if (!inside) return false
        if (my < rect.y + ITEM_H) onWindow() else onPip()
        return true
    }

    companion object {
        private const val WIDTH = 80
        private const val ITEM_H = 18
    }
}
