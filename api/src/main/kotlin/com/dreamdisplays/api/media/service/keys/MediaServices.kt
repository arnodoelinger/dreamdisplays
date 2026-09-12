package com.dreamdisplays.api.media.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.media.search.service.MediaSearchService
import com.dreamdisplays.api.media.source.service.MediaResolverRegistry
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.runtime.registry.model.serviceKey

/**
 * Media service keys.
 *
 * @since 1.8.x
 */
@Unstable
object MediaServices {
    /** Ordered resolver chain for media sources. */
    val RESOLVER_REGISTRY: ServiceKey<MediaResolverRegistry> = serviceKey("dreamdisplays:media_resolver_registry")

    /** Search and related-video lookup service. */
    val SEARCH: ServiceKey<MediaSearchService> = serviceKey("dreamdisplays:media_search")
}
