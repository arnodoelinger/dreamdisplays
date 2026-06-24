package com.dreamdisplays.platform.client.render

import com.dreamdisplays.api.display.model.DisplayBounds
import com.dreamdisplays.api.display.model.DisplayId
import com.dreamdisplays.api.render.TextureHandle

/**
 * Represents an entry for rendering a display. This data class encapsulates all the necessary information for rendering a
 * display, including its ID, bounds, texture handle, brightness, and visibility.
 */
data class DisplayRenderEntry(
    /** The unique identifier for the display. */
    val displayId: DisplayId,

    /** The bounds of the display in world coordinates, used for rendering and hit detection. */
    val bounds: DisplayBounds,

    /** The handle to the texture to be rendered on the display. This should be updated whenever the display's content changes. */
    val textureHandle: TextureHandle,

    /** The brightness level of the display, where 0.0 is completely dark and 1.0 is full brightness. This can be used to simulate dimming or power-saving modes. */
    val brightness: Float,

    /** Whether the display is currently visible and should be rendered. This can be used to optimize rendering by skipping invisible displays. */
    val isVisible: Boolean,
)
