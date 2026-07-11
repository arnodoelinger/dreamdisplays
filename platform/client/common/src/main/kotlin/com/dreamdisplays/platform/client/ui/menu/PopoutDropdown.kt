package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.UiRect
import net.minecraft.client.Minecraft

/**
 * The small popup ("Window" / "In-game" / "Fullscreen" / "Borderless") that opens above the popout
 * button. Owns its visibility, hit-testing, and drawing; the menu only toggles it and forwards
 * clicks.
 *
 * @param onWindow invoked when the user picks the GLFW window mode.
 * @param onPip invoked when the user picks the in-game PiP mode.
 * @param onFullscreen invoked when the user picks the standard fullscreen mode.
 * @param onBorderless invoked when the user picks the immersive (borderless) fullscreen mode.
 */
class PopoutDropdown(
    onWindow: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit,
    onBorderless: () -> Unit,
) {
    var visible: Boolean = false

    /** Item labels and actions in display order. */
    private val items: List<Pair<String, () -> Unit>> = listOf(
        "Window" to onWindow,
        "In-game" to onPip,
        "Fullscreen" to onFullscreen,
        "Borderless" to onBorderless,
    )

    private var rect = UiRect(0, 0, WIDTH, ITEM_H * items.size)

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
        val height = ITEM_H * items.size
        rect = UiRect(anchorX, anchorY - height - 2, WIDTH, height)
        val (x, y) = rect.x to rect.y
        g.fill(x, y, x + WIDTH, y + height, 0xFF1C1C1C.toInt())
        g.fill(x, y, x + WIDTH, y + 1, 0xFF555555.toInt())
        for (i in 1 until items.size) {
            g.fill(x, y + ITEM_H * i, x + WIDTH, y + ITEM_H * i + 1, 0xFF333333.toInt())
        }
        g.fill(x, y + height - 1, x + WIDTH, y + height, 0xFF555555.toInt())
        g.fill(x, y, x + 1, y + height, 0xFF555555.toInt())
        g.fill(x + WIDTH - 1, y, x + WIDTH, y + height, 0xFF555555.toInt())
        val font = Minecraft.getInstance().font
        val fy = y + (ITEM_H - font.lineHeight) / 2
        items.forEachIndexed { i, (label, _) ->
            val color = if (i == 0) 0xFFFFFFFF.toInt() else 0xFFDDDDDD.toInt()
            g.drawText(font, label, x + 6, fy + ITEM_H * i, color, false)
        }
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
        val index = ((my - rect.y) / ITEM_H).coerceIn(0, items.size - 1)
        items[index].second()
        return true
    }

    companion object {
        private const val WIDTH = 80
        private const val ITEM_H = 18
    }
}
