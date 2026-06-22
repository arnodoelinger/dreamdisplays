package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/** Resolves a [MediaSource] into playable streams and metadata. */
@DreamDisplaysUnstableApi
interface MediaResolver {
    /** Higher-priority resolvers are preferred when several can resolve the same source. */
    val priority: Int get() = 0

    /** True when this resolver knows how to resolve [source]. */
    fun canResolve(source: MediaSource): Boolean

    /** Resolves [source] into stream URLs and metadata, or throws a media exception on failure. */
    fun resolve(source: MediaSource): ResolvedMedia

    /** Pre-warms format caches for [source] before playback starts. No-op by default. */
    fun prefetch(source: MediaSource) {}
}
