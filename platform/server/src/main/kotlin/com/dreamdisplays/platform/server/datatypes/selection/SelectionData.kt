package com.dreamdisplays.platform.server.datatypes.selection

/**
 * Shared selection data.
 *
 * Per-platform concrete classes ([PaperSelectionData], [VanillaSelectionData]) carry the actual
 * coordinate types; only readiness state and reset are shared on this interface.
 */
interface SelectionData {
    /** Whether the selection is complete and ready to be used to create a display. */
    var isReady: Boolean

    /** Resets the selection state. */
    fun reset()
}

/**
 * Base shared by [PaperSelectionData] and [VanillaSelectionData], holding the [isReady] flag so
 * platform subclasses only need to declare their own coordinate fields and [reset] the rest.
 */
abstract class BaseSelectionData : SelectionData {
    /** Whether the selection is complete and ready to be used to create a display. */
    override var isReady: Boolean = false
}
