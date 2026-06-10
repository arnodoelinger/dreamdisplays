package com.dreamdisplays.client.ui.kit

/**
 * Immutable integer rectangle used for screen layout: panels and widgets are positioned by slicing
 * and insetting rects instead of repeating raw coordinate arithmetic at every call site.
 *
 * @since 1.8.0
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

    /** Returns the leftmost [width] columns of this rect. */
    fun takeLeft(width: Int): UiRect = UiRect(x, y, width, h)

    /** Returns the rightmost [width] columns of this rect. */
    fun takeRight(width: Int): UiRect = UiRect(right - width, y, width, h)

    /** Returns the top [height] rows of this rect. */
    fun takeTop(height: Int): UiRect = UiRect(x, y, w, height)

    /** Returns the bottom [height] rows of this rect. */
    fun takeBottom(height: Int): UiRect = UiRect(x, bottom - height, w, height)

    /** Returns this rect without its leftmost [amount] columns. */
    fun dropLeft(amount: Int): UiRect = UiRect(x + amount, y, w - amount, h)

    /** Returns this rect without its rightmost [amount] columns. */
    fun dropRight(amount: Int): UiRect = UiRect(x, y, w - amount, h)

    /** Returns this rect without its top [amount] rows. */
    fun dropTop(amount: Int): UiRect = UiRect(x, y + amount, w, h - amount)

    /** Returns this rect without its bottom [amount] rows. */
    fun dropBottom(amount: Int): UiRect = UiRect(x, y, w, h - amount)

    /** Returns a [width] x [height] rect centered inside this one. */
    fun centered(width: Int, height: Int): UiRect =
        UiRect(x + (w - width) / 2, y + (h - height) / 2, width, height)
}
