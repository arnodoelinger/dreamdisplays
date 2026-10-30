package com.dreamdisplays.api.media.source

/**
 * Supplies the ordered set of [MediaResolverService]s a [MediaResolverRegistry] is assembled from.
 *
 * @since 1.8.x
 */
fun interface MediaResolverProvider {
    /** The resolvers to register, in any order; [MediaResolverRegistry] sorts by [MediaResolverService.priority]. */
    fun resolvers(): List<MediaResolverService>
}
