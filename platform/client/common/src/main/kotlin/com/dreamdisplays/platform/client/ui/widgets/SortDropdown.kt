package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import com.dreamdisplays.platform.client.ui.kit.*
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * Small popup listing [SortOption]s, opened from the suggestions panel's sort button. Same visual /
 * interaction pattern as [com.dreamdisplays.platform.client.ui.menu.PopoutDropdown]: a static item
 * list, anchor-centered grow animation, and self-contained hit-testing — but it also tints the
 * currently active option, the same way
 * [com.dreamdisplays.platform.client.ui.menu.AudioTrackDropdown] highlights the playing track.
 *
 * @param current supplies the currently selected option each frame.
 * @param onSelect invoked with the picked option.
 */
class SortDropdown(
    private val current: () -> SortOption,
    private val onSelect: (SortOption) -> Unit,
) {
    var visible: Boolean = false

    private val items: List<SortOption> = SortOption.entries.toList()

    private var rect = UiRect(0, 0, WIDTH, ITEM_H * items.size)

    /** Appear / disappear progress in 0..1, eased the same way as [com.dreamdisplays.platform.client.ui.menu.PopoutDropdown]. */
    private var animProgress = 0f
    private var lastFrameNanos = 0L

    /** Toggles dropdown visibility. */
    fun toggle() {
        visible = !visible
    }

    /** Hides the dropdown. */
    fun hide() {
        visible = false
    }

    /**
     * Draws the dropdown anchored below ([anchorCenterX], [anchorY]) — the sort button sits near the
     * top of the suggestions panel, so unlike [com.dreamdisplays.platform.client.ui.menu.PopoutDropdown]
     * (which opens upward from a bottom-of-screen button) this one grows downward — horizontally
     * centered on [anchorCenterX], easing in / out of [visible].
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
        rect = UiRect(anchorCenterX - WIDTH / 2, anchorY + 2, WIDTH, height)

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
        val active = items.indexOf(current())

        val font = Minecraft.getInstance().font
        val fy = rect.y + (ITEM_H - font.lineHeight) / 2
        items.forEachIndexed { i, option ->
            val itemY = rect.y + ITEM_H * i
            if (i == hovered) {
                g.fill(
                    rect.x + 1, itemY, rect.x + WIDTH - 1, itemY + ITEM_H,
                    scaleAlpha(UiTheme.HOVER_FILL, animProgress),
                )
                g.drawOutline(
                    UiRect(rect.x + 1, itemY, WIDTH - 2, ITEM_H),
                    scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress),
                )
            } else if (i == active) {
                g.fill(rect.x + 1, itemY, rect.x + WIDTH - 1, itemY + ITEM_H, scaleAlpha(ACTIVE_FILL, animProgress))
            }
            val color = scaleAlpha(
                if (i == hovered || i == active) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM,
                animProgress,
            )
            g.drawText(font, UiText.trim(font, option.label(), WIDTH - 10), rect.x + 6, fy + ITEM_H * i, color, false)
        }

        //? if >=1.21.11 {
        if (visible && hovered >= 0 && animProgress > 0.5f) g.requestCursor(CursorTypes.POINTING_HAND)
        //?}

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
    }

    /**
     * Handles a left click while visible: picks an item if the click is inside, always hides.
     * Returns true if the click landed inside the dropdown's rect (consumed either way).
     */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        visible = false
        if (!inside) return false
        val index = ((my - rect.y) / ITEM_H).coerceIn(0, items.size - 1)
        val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        onSelect(items[index])
        return true
    }

    companion object {
        private const val WIDTH = 96
        private const val ITEM_H = 18

        /** Tint for the currently active sort option's row. */
        private const val ACTIVE_FILL = 0x334A90E2
    }
}
