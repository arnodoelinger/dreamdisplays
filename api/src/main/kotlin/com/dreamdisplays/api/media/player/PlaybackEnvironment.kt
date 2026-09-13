package com.dreamdisplays.api.media.player

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.service.MediaResolverRegistry
import com.dreamdisplays.api.media.stream.service.StreamSelector

/**
 * Cross-cutting platform services a playback engine depends on, bundled so a player can be created
 * with a single environment handle instead of a long constructor. The platform layer supplies one
 * shared implementation.
 *
 * @since 1.8.x
 */
@Unstable
interface PlaybackEnvironment {
    /** Read-only playback configuration. */
    val config: PlaybackConfig

    /** Runs render-thread (GL) work. */
    val renderExecutor: RenderExecutor

    /** Creates per-channel GPU frame uploaders. */
    val uploaderFactory: FrameUploaderFactory

    /** Purges cached URL resolutions on recoverable failures. */
    val cacheInvalidator: CacheInvalidator

    /** Resolver chain used to turn a media URL into playable streams. */
    fun resolverChain(): MediaResolverRegistry

    /** Stream selector used to pick the best video/audio streams. */
    fun streamSelector(): StreamSelector
}
