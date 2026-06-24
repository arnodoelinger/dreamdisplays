package com.dreamdisplays.api.media.source

import com.dreamdisplays.api.DreamDisplaysUnstableApi

/**
 * Ordered registry of media resolvers used by playback and search integrations.
 *
 * @since 1.8.0
 */
@DreamDisplaysUnstableApi
interface MediaResolverRegistry {
    /** Adds [resolver] to the registry. */
    fun register(resolver: MediaResolver)

    /** Removes [resolver] from the registry. */
    fun unregister(resolver: MediaResolver)

    /** Resolves [source] with the highest-priority capable resolver. */
    fun resolve(source: MediaSource): ResolvedMedia

    /** Delegates [MediaResolver.prefetch] to all capable resolvers for [source]. */
    fun prefetch(source: MediaSource)

    /** Current resolver snapshot in selection order. */
    val resolvers: List<MediaResolver>
}
