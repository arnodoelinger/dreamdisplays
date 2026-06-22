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
        /** Id of the display that was hit. */
        val displayId: DisplayId,

        /** World X coordinate of the hit point. */
        val hitX: Double,

        /** World Y coordinate of the hit point. */
        val hitY: Double,

        /** World Z coordinate of the hit point. */
        val hitZ: Double,

        /** Squared distance from the eye to the hit point. */
        val distanceSq: Double,
    ) : RaycastResult
}
