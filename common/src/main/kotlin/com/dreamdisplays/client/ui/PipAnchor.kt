package com.dreamdisplays.client.ui

/**
 * The 8 magnetic snap zones a Picture-in-Picture overlay can dock to (4 corners + 4 edge midpoints).
 * A dragged overlay snaps to the nearest free anchor on release; [position] resolves an anchor to a
 * concrete pixel offset, and [centerFacingCorner] reports which corner of the PiP faces the screen
 * center (used to place the resize grip).
 *
 * @since 1.7.0
 */
enum class PipAnchor {
    /** Top-left corner. */
    TOP_LEFT,

    /** Top edge, horizontally centered. */
    TOP_CENTER,

    /** Top-right corner. */
    TOP_RIGHT,

    /** Left edge, vertically centered. */
    MIDDLE_LEFT,

    /** Right edge, vertically centered. */
    MIDDLE_RIGHT,

    /** Bottom-left corner. */
    BOTTOM_LEFT,

    /** Bottom edge, horizontally centered. */
    BOTTOM_CENTER,

    /** Bottom-right corner. */
    BOTTOM_RIGHT;

    /**
     * Resolves this anchor to the top-left pixel offset of a `pw`x`ph` PiP inside an `sw`x`sh`
     * viewport, keeping margin [m] from the edges it touches.
     */
    fun position(sw: Int, sh: Int, pw: Int, ph: Int, m: Int): Pair<Int, Int> = when (this) {
        TOP_LEFT -> m to m
        TOP_CENTER -> (sw / 2 - pw / 2) to m
        TOP_RIGHT -> (sw - pw - m) to m
        MIDDLE_LEFT -> m to (sh / 2 - ph / 2)
        MIDDLE_RIGHT -> (sw - pw - m) to (sh / 2 - ph / 2)
        BOTTOM_LEFT -> m to (sh - ph - m)
        BOTTOM_CENTER -> (sw / 2 - pw / 2) to (sh - ph - m)
        BOTTOM_RIGHT -> (sw - pw - m) to (sh - ph - m)
    }

    /** Centers the facing corner. */
    fun centerFacingCorner(): Pair<Int, Int> = when (this) {
        TOP_LEFT -> 1 to 1
        TOP_CENTER -> 0 to 1
        TOP_RIGHT -> -1 to 1
        MIDDLE_LEFT -> 1 to 0
        MIDDLE_RIGHT -> -1 to 0
        BOTTOM_LEFT -> 1 to -1
        BOTTOM_CENTER -> 0 to -1
        BOTTOM_RIGHT -> -1 to -1
    }

    companion object {
        /** Maps a coarse [PipCorner] onto its matching corner anchor. */
        fun fromCorner(c: PipCorner): PipAnchor = when (c) {
            PipCorner.TOP_LEFT -> TOP_LEFT
            PipCorner.TOP_RIGHT -> TOP_RIGHT
            PipCorner.BOTTOM_LEFT -> BOTTOM_LEFT
            PipCorner.BOTTOM_RIGHT -> BOTTOM_RIGHT
        }
    }
}
