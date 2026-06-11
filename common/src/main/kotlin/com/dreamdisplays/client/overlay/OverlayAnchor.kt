package com.dreamdisplays.client.overlay

/**
 * Where an overlay docks within its viewport. [snapX] / [snapY] are normalized anchor coordinates
 * (0 = left/top, 1 = right/bottom); [FREE] opts out of snapping and uses the negative sentinel.
 *
 * @property snapX normalized horizontal anchor, 0..1, or -1 for [FREE].
 * @property snapY normalized vertical anchor, 0..1, or -1 for [FREE].
 *
 * @since 1.7.0
 */
enum class OverlayAnchor(val snapX: Float, val snapY: Float) {
    /** Docked to the top-left corner. */
    TOP_LEFT(0f, 0f),

    /** Docked to the top-right corner. */
    TOP_RIGHT(1f, 0f),

    /** Docked to the bottom-left corner. */
    BOTTOM_LEFT(0f, 1f),

    /** Docked to the bottom-right corner. */
    BOTTOM_RIGHT(1f, 1f),

    /** Freely positioned; ignores snapping. */
    FREE(-1f, -1f);

    /** True for any corner anchor, i.e. anything except [FREE]. */
    val isSnapped: Boolean get() = this != FREE
}
