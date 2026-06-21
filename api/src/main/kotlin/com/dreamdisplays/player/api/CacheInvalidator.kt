package com.dreamdisplays.player.api

/** Purges any cached resolution for a media URL so the next resolve hits the network fresh. */
fun interface CacheInvalidator {
    fun invalidate(url: String)
}
