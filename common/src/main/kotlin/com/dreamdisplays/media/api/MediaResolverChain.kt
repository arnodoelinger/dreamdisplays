package com.dreamdisplays.media.api

interface MediaResolverChain {
    fun register(resolver: MediaResolver)
    fun unregister(resolver: MediaResolver)
    fun resolve(source: MediaSource): ResolvedMedia

    /** Delegates [MediaResolver.prefetch] to all capable resolvers for [source]. */
    fun prefetch(source: MediaSource)
    val resolvers: List<MediaResolver>
}
