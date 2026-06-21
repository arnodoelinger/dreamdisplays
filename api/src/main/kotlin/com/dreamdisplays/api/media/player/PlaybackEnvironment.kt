package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.media.MediaResolverChain
import com.dreamdisplays.api.media.StreamSelector

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
    fun resolverChain(): MediaResolverChain

    /** Stream selector used to pick the best video/audio streams. */
    fun streamSelector(): StreamSelector
}
