package com.dreamdisplays.platform.client.input

import com.dreamdisplays.api.display.model.DisplayId

/**
 * Represents the result of a raycast against the displays.
 */
sealed interface RaycastResult {
    /** Represents a raycast that did not hit any display. */
    data object Miss : RaycastResult

    /** Represents a raycast that hit a display. */
    data class Hit(
        val displayId: DisplayId,
        val hitX: Double,
        val hitY: Double,
        val hitZ: Double,
        val distanceSq: Double,
    ) : RaycastResult
}
