package com.dreamdisplays.media.source.kick

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata
import com.dreamdisplays.media.source.platform.YtDlpMetadataFallback

/**
 * Metadata cache for Kick channels and VODs, so a pasted Kick link shows a real title / thumbnail /
 * live badge without waiting for stream resolution. Keyed so a live channel and a VOD never collide.
 *
 * @since 1.9.0
 */
object KickMetadataCache {
    private val cache = PlatformMetadataCache(
        name = "Kick",
        // Live viewer counts and titles change constantly; VODs are static
        liveTtlSeconds = 60,
        staticTtlMinutes = 30,
        // Falls back to yt-dlp when Cloudflare turns away the direct site-API call (see KickApi's
        // datacenter-IP note) — playback already relies on the same fallback, this just borrows it
        // for the card / preview metadata, so those aren't left blank.
        fetch = { key -> sourceFor(key).let { KickApi.metadata(it) ?: YtDlpMetadataFallback.fetch(it.url) } },
    )

    /** The cache key for [source]: `video:<uuid>` for a VOD, `channel:<slug>` for a live channel. */
    fun cacheKey(source: MediaSource.Kick): String? = when {
        source.videoUuid != null -> "video:${source.videoUuid}"
        source.channel != null -> "channel:${source.channel}"
        else -> null
    }

    /** Reconstructs the [MediaSource.Kick] the fetch needs from a [cacheKey]. */
    private fun sourceFor(key: String): MediaSource.Kick {
        val value = key.substringAfter(':')
        return if (key.startsWith("video:")) {
            MediaSource.Kick(url = "https://kick.com/video/$value", videoUuid = value)
        } else {
            MediaSource.Kick(url = "https://kick.com/$value", channel = value)
        }
    }

    /** Returns cached metadata for [key], or null when not yet fetched. */
    fun get(key: String): PlatformVideoMetadata? = cache.get(key)

    /** Seeds [metadata] for [source] (used by [KickResolver] after it fetches the API). */
    fun put(source: MediaSource.Kick, metadata: PlatformVideoMetadata) {
        cacheKey(source)?.let { cache.put(it, metadata) }
    }

    /** Warms the cache for [source] in the background. */
    fun requestAsync(source: MediaSource.Kick) {
        cacheKey(source)?.let { cache.requestAsync(it) }
    }

    /** Fetches metadata for [source] now (blocking); for background search threads. */
    fun resolveBlocking(source: MediaSource.Kick): PlatformVideoMetadata? =
        cacheKey(source)?.let { cache.resolveBlocking(it) }
}
