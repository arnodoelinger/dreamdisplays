package com.dreamdisplays.client.ui

/**
 * The four screen corners a Picture-in-Picture overlay can be opened in. A coarse, user-facing
 * placement hint, mapped onto the finer [PipAnchor] grid via [PipAnchor.fromCorner].
 *
 * @since 1.7.0
 */
enum class PipCorner {
    /** Top-left corner of the screen. */
    TOP_LEFT,

    /** Top-right corner of the screen. */
    TOP_RIGHT,

    /** Bottom-left corner of the screen. */
    BOTTOM_LEFT,

    /** Bottom-right corner of the screen. */
    BOTTOM_RIGHT,
}
