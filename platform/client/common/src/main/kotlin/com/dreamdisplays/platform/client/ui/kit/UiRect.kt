package com.dreamdisplays.platform.client.ui.kit

/**
 * Immutable integer rectangle used for screen layout: panels and widgets are positioned by slicing
 * and insetting rects instead of repeating raw coordinate arithmetic at every call site.
 */
data class UiRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2

    /** Returns true if ([px], [py]) lies inside this rect (right/bottom exclusive). */
    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom

    /** Returns this rect shrunk by [amount] on every side. */
    fun inset(amount: Int): UiRect = inset(amount, amount)

    /** Returns this rect shrunk by [dx] horizontally and [dy] vertically on each side. */
    fun inset(dx: Int, dy: Int): UiRect = UiRect(x + dx, y + dy, w - dx * 2, h - dy * 2)

    /** Returns a [width] x [height] rect centered inside this one. */
    fun centered(width: Int, height: Int): UiRect =
        UiRect(x + (w - width) / 2, y + (h - height) / 2, width, height)
}
