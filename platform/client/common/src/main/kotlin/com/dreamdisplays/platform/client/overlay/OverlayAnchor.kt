package com.dreamdisplays.platform.client.overlay

/**
 * Where an overlay docks within its viewport. [snapX] / [snapY] are normalized anchor coordinates
 * (0 = left / top, 1 = right / bottom).
 */
enum class OverlayAnchor(val snapX: Float, val snapY: Float) {
    /** Freely positioned; ignores snapping. */
    FREE(-1f, -1f);
}
