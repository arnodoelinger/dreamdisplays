package com.dreamdisplays.platform.client.overlay

/**
 * Determines whether the crosshair should be suppressed.
 */
fun interface CrosshairPolicy {
    /** Returns true if the crosshair should be suppressed. */
    fun shouldSuppressCrosshair(): Boolean
}
