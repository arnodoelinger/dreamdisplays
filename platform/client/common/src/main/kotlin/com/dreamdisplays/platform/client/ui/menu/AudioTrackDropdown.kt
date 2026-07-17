package com.dreamdisplays.platform.client.ui.menu

import com.dreamdisplays.api.media.stream.MediaStream
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
 * The popup that opens above the audio-track button, listing the current video's selectable audio
 * tracks. Same visual / interaction pattern as [PopoutDropdown], but its items are dynamic — the
 * track list is resolved per video — so it refreshes from [getTracks] every time it becomes visible
 * and highlights the currently playing track.
 *
 * @param getTracks supplies the audio tracks available for the current video.
 * @param currentUrl the resolved URL of the track currently playing (highlighted in the list).
 * @param onSelect invoked with the track the user picked.
 */
class AudioTrackDropdown(
    private val getTracks: () -> List<MediaStream>,
    private val currentUrl: () -> String,
    private val onSelect: (MediaStream) -> Unit,
) {
    var visible: Boolean = false

    /** Tracks shown in display order; refreshed from [getTracks] each time the dropdown opens. */
    private var items: List<MediaStream> = emptyList()

    private var rect = UiRect(0, 0, WIDTH, ITEM_H)

    /** Appear / disappear progress in 0..1, eased the same way as [PopoutDropdown]. */
    private var animProgress = 0f
    private var lastFrameNanos = 0L

    /** Toggles visibility; snapshots the current track list when opening. */
    fun toggle() {
        visible = !visible
        if (visible) items = getTracks()
    }

    /** Hides the dropdown. */
    fun hide() {
        visible = false
    }

    /** Label for [stream]: its track name, else its language code, else a 1-based index fallback. */
    private fun label(stream: MediaStream, index: Int): String =
        stream.audioTrackName ?: stream.audioTrackLang ?: "Track ${index + 1}"

    /** Draws the dropdown anchored above the rect at ([anchorX], [anchorY]), easing in / out of [visible]. */
    fun draw(g: GuiGraphicsCompat, anchorX: Int, anchorY: Int, mouseX: Int, mouseY: Int) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        val target = if (visible) 1f else 0f
        animProgress += (target - animProgress) * minOf(1f, dt * 12f)
        if (animProgress < 0.01f) {
            animProgress = 0f
            return
        }

        val count = items.size.coerceAtLeast(1)
        val height = ITEM_H * count
        rect = UiRect(anchorX, anchorY - height - 2, WIDTH, height)

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
        val activeUrl = currentUrl()

        val font = Minecraft.getInstance().font
        val fy = rect.y + (ITEM_H - font.lineHeight) / 2
        items.forEachIndexed { i, stream ->
            val itemY = rect.y + ITEM_H * i
            val isActive = stream.url == activeUrl
            when {
                i == hovered -> {
                    g.fill(rect.x + 1, itemY, rect.x + WIDTH - 1, itemY + ITEM_H, scaleAlpha(UiTheme.HOVER_FILL, animProgress))
                    g.drawOutline(UiRect(rect.x + 1, itemY, WIDTH - 2, ITEM_H), scaleAlpha(UiTheme.CARD_BORDER_HOVER, animProgress))
                }
                // The playing track keeps a faint accent tint so it's always visible, even unhovered
                isActive -> g.fill(rect.x + 1, itemY, rect.x + WIDTH - 1, itemY + ITEM_H, scaleAlpha(ACTIVE_FILL, animProgress))
            }
            val color = scaleAlpha(if (i == hovered || isActive) UiTheme.TEXT_PRIMARY else UiTheme.TEXT_DIM, animProgress)
            g.drawText(font, label(stream, i), rect.x + 6, fy + ITEM_H * i, color, false)
        }

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/
    }

    /**
     * Handles a left click while visible: picks a track if the click is inside, always hides.
     * Returns true if a track was picked (the click is consumed).
     */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (!visible) return false
        val inside = mx in rect.x..rect.right && my in rect.y..rect.bottom
        visible = false
        if (!inside || items.isEmpty()) return false
        val index = ((my - rect.y) / ITEM_H).coerceIn(0, items.size - 1)
        val s = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f)
        Minecraft.getInstance().soundManager.play(s)
        onSelect(items[index])
        return true
    }

    companion object {
        private const val WIDTH = 90
        private const val ITEM_H = 18

        /** Faint blue tint marking the currently playing track (accent at low alpha). */
        private const val ACTIVE_FILL = 0x334A90E2
    }
}
