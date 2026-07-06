package com.dreamdisplays.platform.client.core.modules

import com.dreamdisplays.api.media.MediaServices
import com.dreamdisplays.api.media.stream.StreamSelector
import com.dreamdisplays.api.runtime.DreamDisplaysModule
import com.dreamdisplays.api.runtime.ModuleContext
import com.dreamdisplays.api.runtime.register
import com.dreamdisplays.media.source.DefaultMediaResolverProvider
import com.dreamdisplays.media.source.DefaultMediaResolverRegistry
import com.dreamdisplays.media.source.DefaultStreamSelector
import com.dreamdisplays.media.source.YtDlpSearchService
import com.dreamdisplays.media.source.ytdlp.ResolverConfig
import com.dreamdisplays.platform.client.managers.ClientStateManager

/** Installs the media resolver chain, search service, and stream selector. */
object MediaResolverModule : DreamDisplaysModule {
    /** Media resolver module. */
    override val id: String = "dreamdisplays:media_resolver"

    /** Installs the media resolver chain, search service, and stream selector. */
    override fun install(context: ModuleContext) {
        ResolverConfig.provider = object : ResolverConfig.Provider {
            override val ytdlpProxy: String get() = ClientStateManager.config.ytdlpProxy
            override val ytdlpCookieSource get() = ClientStateManager.config.ytdlpCookieSource
        }

        val resolverChain = DefaultMediaResolverRegistry().apply {
            DefaultMediaResolverProvider.resolvers().forEach(::register)
        }

        val services = context.services
        services.register(MediaServices.RESOLVER_REGISTRY, resolverChain)
        services.register(MediaServices.SEARCH, YtDlpSearchService())
        services.register<StreamSelector>(DefaultStreamSelector())
    }
}
