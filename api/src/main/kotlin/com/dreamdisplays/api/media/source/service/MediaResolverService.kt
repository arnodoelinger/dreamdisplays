package com.dreamdisplays.api.media.source.service

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia

/**
 * Resolves a [MediaSource] into playable streams and metadata.
 *
 * @since 1.8.x
 */
@Unstable
interface MediaResolverService {
    /** Higher-priority resolvers are preferred when several can resolve the same source. */
    val priority: Int get() = 0

    /** True when this resolver knows how to resolve [source]. */
    fun canResolve(source: MediaSource): Boolean

    /** Resolves [source] into stream URLs and metadata, or throws a media exception on failure. */
    fun resolve(source: MediaSource): ResolvedMedia

    /** Pre-warms resolver caches for [source]; return true if a usable result was warmed. */
    fun prefetch(source: MediaSource): Boolean = false
}
