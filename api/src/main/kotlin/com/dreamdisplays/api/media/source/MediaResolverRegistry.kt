package com.dreamdisplays.api.media.source

/**
 * Ordered registry of media resolvers used by playback and search integrations.
 *
 * @since 1.8.x
 */
interface MediaResolverRegistry {
    /** Adds [resolver] to the registry. */
    fun register(resolver: MediaResolverService)

    /** Removes [resolver] from the registry. */
    fun unregister(resolver: MediaResolverService)

    /** Resolves [source] with the highest-priority capable resolver. */
    fun resolve(source: MediaSource): ResolvedMedia

    /** Delegates [MediaResolverService.prefetch] to all capable resolvers for [source]. */
    fun prefetch(source: MediaSource)

    /** Current resolver snapshot in selection order. */
    val resolvers: List<MediaResolverService>
}
