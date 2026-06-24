package com.dreamdisplays.media.player

import com.dreamdisplays.api.media.player.FrameUploaderFactory
import com.dreamdisplays.api.media.player.RenderThreadExecutor
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.media.player.cache.CacheInvalidator
import com.dreamdisplays.media.player.stream.StreamSelector

/**
 * Cross-cutting platform services a [com.dreamdisplays.media.player.MediaPlayer] depends on, bundled so a
 * player can be created with a single environment handle instead of a long constructor. The platform
 * layer supplies one shared implementation.
 */
interface PlaybackEnvironment {
    /** Read-only playback configuration. */
    val config: PlaybackConfig

    /** Runs render-thread (GL) work. */
    val renderExecutor: RenderThreadExecutor

    /** Creates per-channel GPU frame uploaders. */
    val uploaderFactory: FrameUploaderFactory

    /** Purges cached URL resolutions on recoverable failures. */
    val cacheInvalidator: CacheInvalidator

    /** Resolver chain used to turn a media URL into playable streams. */
    fun resolverChain(): MediaResolverRegistry

    /** Stream selector used to pick the best video/audio streams. */
    fun streamSelector(): StreamSelector
}
