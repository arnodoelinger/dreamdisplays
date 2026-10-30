package com.dreamdisplays.api.media.player

/**
 * Purges any cached resolution for a media URL so the next resolve hits the network fresh.
 *
 * @since 1.8.x
 */
fun interface CacheInvalidator {
    fun invalidate(url: String)
}
