package com.dreamdisplays.client.ui.menu

import com.dreamdisplays.client.ui.GuiGraphicsCompat
import com.dreamdisplays.client.ui.drawText
import com.dreamdisplays.client.ui.kit.UiRect
import com.dreamdisplays.utils.GeneralUtil
import com.dreamdisplays.utils.UpdateCheck
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import java.awt.Desktop
import java.net.URI
import kotlin.math.sin

/**
 * The "Dream Displays x.y.z" label in the screen corner. Shows a bouncing update arrow when a newer
 * version exists and opens the Modrinth page when clicked in that state.
 *
 * @since 1.0.0
 */
class ModTitleLabel {
    private val openedAtMs: Long = System.currentTimeMillis()
    private var hoverArea: UiRect? = null

    /** Draws the label (and the update arrow when applicable) at ([x], [y]). */
    fun draw(g: GuiGraphicsCompat, x: Int, y: Int) {
        val font = Minecraft.getInstance().font
        val name = Component.literal("Dream Displays")
        val ver = Component.literal(" ${GeneralUtil.getModVersion()}")
            .withStyle(Style.EMPTY.withColor(0xFF6AB7FF.toInt()))
        val label = name.copy().append(ver)
        g.drawText(font, label, x, y, 0xFFFFFFFF.toInt(), true)

        val textW = font.width(label)
        var totalW = textW
        if (UpdateCheck.shouldShowArrow()) {
            val t = ((System.currentTimeMillis() - openedAtMs) % 1800L) / 1800f
            var arrowYOffset = 0
            if (t < 0.25f) {
                val p = t / 0.25f
                arrowYOffset = (-sin(p * Math.PI) * 3.0).toInt()
            }
            val arrow = Component.literal(" ▲")
                .withStyle(Style.EMPTY.withColor(0xFFFF4040.toInt()))
            g.drawText(font, arrow, x + textW, y + arrowYOffset, 0xFFFFFFFF.toInt(), true)
            totalW += font.width(arrow)
        }
        hoverArea = UiRect(x, y - 1, totalW, font.lineHeight + 2)
    }

    /** Opens the Modrinth versions page if an update arrow is shown and the click hits the label. */
    fun handleClick(mx: Int, my: Int): Boolean {
        if (!UpdateCheck.shouldShowArrow()) return false
        if (hoverArea?.contains(mx, my) != true) return false
        try {
            Desktop.getDesktop().browse(URI.create(MODRINTH_URL))
        } catch (_: Exception) {
        }
        return true
    }

    companion object {
        private const val MODRINTH_URL = "https://modrinth.com/plugin/dreamdisplays/versions"
    }
}
