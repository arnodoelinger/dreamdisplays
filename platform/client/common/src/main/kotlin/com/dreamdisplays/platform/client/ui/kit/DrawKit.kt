package com.dreamdisplays.platform.client.ui.kit

import com.dreamdisplays.platform.client.Initializer
import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.drawText
import net.minecraft.client.gui.Font
//? if >=26 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?} else
/*import net.minecraft.client.gui.GuiGraphics*/
import net.minecraft.client.gui.components.AbstractWidget
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

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

/**
 * Fills the rect [x1,y1)-[x2,y2) with a top-to-bottom gradient from [top] to [bottom] (both ARGB).
 * Drawn as 1px horizontal strips so it only relies on plain [fill] and works on every render backend.
 */
fun GuiGraphicsCompat.fillVGradient(x1: Int, y1: Int, x2: Int, y2: Int, top: Int, bottom: Int) {
    val h = y2 - y1
    if (h <= 0 || x2 <= x1) return
    for (i in 0 until h) {
        val t = if (h == 1) 0f else i / (h - 1f)
        fill(x1, y1 + i, x2, y1 + i + 1, lerpArgb(top, bottom, t))
    }
}

/**
 * Draws an animated loading shimmer inside [x1, y1) - [x2, y2): a subtle top-to-bottom [base] gradient
 * (so the card doesn't read as dead flat) with a soft [highlight] band sweeping left-to-right, eased
 * in and out like a glare rather than a hard-edged triangle. Time-driven, so it animates on its own
 * each frame with no state to keep.
 */
fun GuiGraphicsCompat.drawShimmer(x1: Int, y1: Int, x2: Int, y2: Int, base: Int, highlight: Int) {
    val w = x2 - x1
    val h = y2 - y1
    if (w <= 0 || h <= 0) return
    fillVGradient(x1, y1, x2, y2, lightenRgb(base, 0.06f), base)

    val period = 1600L
    val phase = (System.currentTimeMillis() % period) / period.toFloat()
    val centre = x1 - w * 0.5f + (w * 2f) * phase
    val half = (w * 0.3f).coerceAtLeast(6f)
    for (i in 0 until w) {
        val d = (kotlin.math.abs((x1 + i) + 0.5f - centre) / half).coerceIn(0f, 1f)
        if (d >= 1f) continue
        val e = 1f - d
        val a = e * e * (3f - 2f * e)
        fill(x1 + i, y1, x1 + i + 1, y2, scaleAlpha(highlight, a))
    }
}

/** Linear ARGB interpolation between [a] and [b] at [t] in 0..1. */
fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val ct = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val ca = (a ushr shift) and 0xFF
        val cb = (b ushr shift) and 0xFF
        return (ca + (cb - ca) * ct).toInt() and 0xFF
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Returns [color] with its alpha channel multiplied by [factor] (0..1). */
fun scaleAlpha(color: Int, factor: Float): Int {
    val a = (((color ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)).toInt() and 0xFF
    return (a shl 24) or (color and 0x00FFFFFF)
}

/** Returns [color] with its RGB channels scaled by [factor] (alpha preserved); used for dimming. */
fun darkenRgb(color: Int, factor: Float): Int {
    val f = factor.coerceIn(0f, 1f)
    val r = (((color ushr 16) and 0xFF) * f).toInt() and 0xFF
    val g = (((color ushr 8) and 0xFF) * f).toInt() and 0xFF
    val b = ((color and 0xFF) * f).toInt() and 0xFF
    return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
}

/** Returns [color] mixed toward white by [factor] (0..1), forced fully opaque; used for accent tints. */
fun lightenRgb(color: Int, factor: Float): Int {
    val f = factor.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val c = (color ushr shift) and 0xFF
        return (c + (0xFF - c) * f).toInt() and 0xFF
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Nine-slice panel background sprite; see textures/gui/sprites/widgets/panel.png. */
val PANEL_SPRITE: Identifier = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/panel")

/** Nine-slice dropdown background sprite; see textures/gui/sprites/widgets/dropdown.png. */
val DROPDOWN_SPRITE: Identifier = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "widgets/dropdown")

/**
 * Draws a nine-slice textured panel background filling [r], additionally faded by [alpha] (1 = the
 * sprite's own baked-in translucency, unchanged).
 */
fun GuiGraphicsCompat.drawPanelSprite(r: UiRect, sprite: Identifier = PANEL_SPRITE, alpha: Float = 1f) {
    //? if >=1.21.11 {
    blitSprite(RenderPipelines.GUI_TEXTURED, sprite, r.x, r.y, r.w, r.h, ARGB.white(alpha))
    //?} else
    /*blitSprite(sprite, r.x, r.y, r.w, r.h)*/
}

/** Draws a nine-slice panel background filling [r] and [title] in the top-left padding corner. */
fun GuiGraphicsCompat.drawPanel(font: Font, r: UiRect, title: String) {
    drawPanelSprite(r)
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
