package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.DROPDOWN_SPRITE
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.drawOutline
import com.dreamdisplays.platform.client.ui.kit.drawPanelSprite
import com.dreamdisplays.platform.client.ui.kit.scaleAlpha
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

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

    /** Appear / disappear progress in 0..1, eased the same way as [com.dreamdisplays.platform.client.ui.PipOverlay]. */
    private var animProgress = 0f
    private var lastFrameNanos = 0L

    /** Toggles dropdown visibility (popout button behavior when no popout is active). */
    fun toggle() {
        visible = !visible
    }

    /** Hides the dropdown. */
    fun hide() {
        visible = false
    }

    /**
     * Draws the dropdown anchored above ([anchorCenterX], [anchorY]), horizontally centered on that
     * point (the button's own center) rather than growing out from one of its edges — easing in / out
     * of [visible].
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

        val height = ITEM_H * items.size
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

        val hovered = if (visible && rect.contains(mouseX, mouseY)) (mouseY - rect.y) / ITEM_H else -1

        val font = Minecraft.getInstance().font
        val fy = rect.y + (ITEM_H - font.lineHeight) / 2
        items.forEachIndexed { i, (label, _) ->
            val itemY = rect.y + ITEM_H * i
            if (i == hovered) {
                g.fill(rect.x + 1, itemY, rect.x + WIDTH - 1, itemY + ITEM_H, scaleAlpha(UiTheme.HOVER_FILL, animProgress))
                g.drawOutline(UiRect(rect.x + 1, itemY, WIDTH - 2, ITEM_H), scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress))
            }
            val color = scaleAlpha(if (i == hovered) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, animProgress)
            g.drawText(font, label, rect.x + 6, fy + ITEM_H * i, color, false)
        }

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
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
        val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        items[index].second()
        return true
    }

    companion object {
        private const val WIDTH = 80
        private const val ITEM_H = 18
    }
}
