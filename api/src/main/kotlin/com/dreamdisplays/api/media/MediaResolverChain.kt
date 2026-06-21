package com.dreamdisplays.api.media


interface MediaResolverChain {
    fun register(resolver: MediaResolver)
    fun unregister(resolver: MediaResolver)
    fun resolve(source: MediaSource): ResolvedMedia

    /** Delegates [MediaResolver.prefetch] to all capable resolvers for [source]. */
    fun prefetch(source: MediaSource)
    val resolvers: List<MediaResolver>
}
