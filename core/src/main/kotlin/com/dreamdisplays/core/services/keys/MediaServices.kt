package com.dreamdisplays.core.services.keys

import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.core.runtime.ServiceKey

/** Media service keys. */
object MediaServices {
    /** Ordered resolver chain for media sources. */
    val RESOLVER_REGISTRY: ServiceKey<MediaResolverRegistry> =
        ServiceKey("dreamdisplays:media_resolver_registry", MediaResolverRegistry::class.java)

    /** Search and related-video lookup service. */
    val SEARCH: ServiceKey<MediaSearchService> =
        ServiceKey("dreamdisplays:media_search", MediaSearchService::class.java)
}
