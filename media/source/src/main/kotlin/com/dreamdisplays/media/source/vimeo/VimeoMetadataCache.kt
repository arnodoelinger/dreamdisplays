package com.dreamdisplays.media.source.vimeo

import com.dreamdisplays.api.media.source.MediaSource
import com.dreamdisplays.media.source.platform.PlatformMetadataCache
import com.dreamdisplays.media.source.platform.PlatformVideoMetadata

/**
 * Metadata cache for Vimeo videos, so the menu can show a real title / uploader / thumbnail on a
 * pasted Vimeo link without waiting for full stream resolution. Keyed by video id plus its optional
 * unlisted hash, since the hash is what authorizes the fetch.
 *
 * @since 1.9.0
 */
object VimeoMetadataCache {
    private val cache = PlatformMetadataCache(
        name = "Vimeo",
        // Vimeo has live events, but they are rare; a short TTL still keeps a live title current
        liveTtlSeconds = 60,
        staticTtlMinutes = 30,
        fetch = { key -> VimeoApi.metadata(sourceFor(key)) },
    )

    /** The cache key for [source]: `<videoId>` or `<videoId>/<hash>` for an unlisted video. */
    fun cacheKey(source: MediaSource.Vimeo): String =
        if (source.hash != null) "${source.videoId}/${source.hash}" else source.videoId

    /** Reconstructs the [MediaSource.Vimeo] the fetch needs from a [cacheKey]. */
    private fun sourceFor(key: String): MediaSource.Vimeo {
        val videoId = key.substringBefore('/')
        val hash = key.substringAfter('/', "").takeIf { it.isNotEmpty() }
        return MediaSource.Vimeo(url = "https://vimeo.com/$videoId", videoId = videoId, hash = hash)
    }

    /** Returns cached metadata for [key], or null when not yet fetched. */
    fun get(key: String): PlatformVideoMetadata? = cache.get(key)

    /** Seeds [metadata] for [source] (used by [VimeoResolver] after it fetches the config). */
    fun put(source: MediaSource.Vimeo, metadata: PlatformVideoMetadata) = cache.put(cacheKey(source), metadata)

    /** Warms the cache for [source] in the background. */
    fun requestAsync(source: MediaSource.Vimeo) = cache.requestAsync(cacheKey(source))

    /** Fetches metadata for [source] now (blocking); for background search threads. */
    fun resolveBlocking(source: MediaSource.Vimeo): PlatformVideoMetadata? = cache.resolveBlocking(cacheKey(source))
}
