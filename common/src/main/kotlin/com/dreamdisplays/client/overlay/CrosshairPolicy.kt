package com.dreamdisplays.client.overlay

/**
 * Determines whether the crosshair should be suppressed.
 *
 * @since 1.0.0
 */
fun interface CrosshairPolicy {
    /** Returns true if the crosshair should be suppressed. */
    fun shouldSuppressCrosshair(): Boolean
}
