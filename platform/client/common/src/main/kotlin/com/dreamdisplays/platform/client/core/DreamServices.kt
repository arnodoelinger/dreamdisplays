package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.display.service.DisplayService
import com.dreamdisplays.api.playback.PlaybackService
import com.dreamdisplays.api.watchparty.WatchPartyService
import com.dreamdisplays.core.display.service.impl.DefaultDisplayService
import com.dreamdisplays.core.display.service.impl.DefaultDisplaySystem
import com.dreamdisplays.core.playback.DefaultPlaybackService
import com.dreamdisplays.core.watchparty.DefaultWatchPartyService
import com.dreamdisplays.core.display.service.DisplayLookup
import com.dreamdisplays.core.display.service.DisplayMutationPort
import com.dreamdisplays.core.display.service.DisplaySystem
import com.dreamdisplays.core.playback.PlaybackPort
import com.dreamdisplays.core.watchparty.WatchPartyPort
import com.dreamdisplays.platform.client.capabilities.CapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.ClientCapabilityDetector
import com.dreamdisplays.platform.client.capabilities.DefaultCapabilityNegotiationService
import com.dreamdisplays.platform.client.capabilities.MinecraftClientCapabilityDetector
import com.dreamdisplays.platform.client.input.CompositeInputHandler
import com.dreamdisplays.platform.client.input.DefaultKeyBindingRegistry
import com.dreamdisplays.platform.client.input.DisplayInteractionService
import com.dreamdisplays.platform.client.input.DisplayMenuInputHandler
import com.dreamdisplays.platform.client.input.InputHandler
import com.dreamdisplays.platform.client.input.KeyBindingRegistry
import com.dreamdisplays.platform.client.input.MinecraftDisplayInteractionService
import com.dreamdisplays.platform.client.overlay.CrosshairPolicy
import com.dreamdisplays.platform.client.overlay.OverlayManager
import com.dreamdisplays.platform.client.popout.DefaultPopoutManager
import com.dreamdisplays.platform.client.popout.PopoutManager
import com.dreamdisplays.platform.client.render.ClientRenderService
import com.dreamdisplays.platform.client.render.RenderHook
import com.dreamdisplays.platform.client.ui.PipOverlayManager
import com.dreamdisplays.platform.client.displays.MinecraftDisplayCommands
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.media.source.DefaultMediaResolverRegistry
import com.dreamdisplays.media.runtime.DefaultMediaSessionManager
import com.dreamdisplays.media.source.DefaultStreamSelector
import com.dreamdisplays.media.source.YtDlpSearchService
import com.dreamdisplays.api.media.source.MediaResolverRegistry
import com.dreamdisplays.api.media.search.MediaSearchService
import com.dreamdisplays.media.runtime.MediaSessionManager
import com.dreamdisplays.media.player.stream.StreamSelector
import com.dreamdisplays.platform.client.render.AsyncTextureUploader
import com.dreamdisplays.platform.client.render.DefaultDisplayRenderer
import com.dreamdisplays.platform.client.render.ScreenRenderer
import com.dreamdisplays.api.render.DisplayRenderer
import com.dreamdisplays.api.render.TextureUploaderFactory
import com.dreamdisplays.media.source.ytdlp.NewPipeResolver
import com.dreamdisplays.media.source.ytdlp.ResolverConfig
import com.dreamdisplays.media.source.ytdlp.YtDlpResolver

/**
 * Process-wide [ServiceRegistry] holder and bootstrap entry point.
 *
 * Replaces scattered `object` singletons accessed by name with contract-typed lookups:
 * call [registry].`get<MediaResolverRegistry>()` instead of touching the concrete resolver objects.
 * [bootstrap] wires the default service graph and is idempotent, so it is safe to call from each
 * platform's startup path.
 */
object DreamServices {
    /** The shared registry. Services are populated by [bootstrap]. */
    val registry: ServiceRegistry = DefaultServiceRegistry()

    /**
     * Whether [bootstrap] has been called already. Guards against double registration of services, which can cause
     * issues if services have internal state or are expected to be singletons.
     */
    @Volatile
    private var bootstrapped = false

    /**
     * Registers the default service graph exactly once.
     *
     * Media pipeline: [MediaResolverRegistry] ([NewPipeResolver] fast path at priority 10,
     * [YtDlpResolver] subprocess fallback at 0), [MediaSearchService], [MediaSessionManager],
     * and [StreamSelector].
     *
     * Displays and playback: [DisplayService], [PlaybackService], [OverlayManager],
     * [PopoutManager], and [DisplayInteractionService].
     *
     * Client integration: [ClientRenderService] ([ScreenRenderer]), [ClientCapabilityDetector],
     * and [CapabilityNegotiationService].
     *
     * Render: [DisplayRenderer] (orchestrator for API-registered surfaces) and
     * [TextureUploaderFactory] (per-GL-context [AsyncTextureUploader] instances).
     *
     * The loader entrypoint additionally registers a [com.dreamdisplays.api.platform.Platform];
     * when present, [com.dreamdisplays.platform.client.managers.ClientStartupManager] hosts a
     * [ClientApplication] on top of it.
     */
    @Synchronized
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true

        // Bridge the platform-agnostic media resolvers to the live client config (proxy / browser cookies).
        ResolverConfig.provider = object : ResolverConfig.Provider {
            override val ytdlpProxy: String get() = ClientStateManager.config.ytdlpProxy
            override val ytdlpCookiesFromBrowser: String get() = ClientStateManager.config.ytdlpCookiesFromBrowser
        }

        val resolverChain = DefaultMediaResolverRegistry().apply {
            register(NewPipeResolver)
            register(YtDlpResolver)
        }

        val displaySystem = DefaultDisplaySystem(MinecraftDisplayCommands())
        val displayService = DefaultDisplayService(displaySystem, displaySystem)
        val playbackService = DefaultPlaybackService(displaySystem)

        registry.register<DisplaySystem>(displaySystem)
        registry.register<DisplayLookup>(displaySystem)
        registry.register<DisplayMutationPort>(displaySystem)
        registry.register<PlaybackPort>(displaySystem)
        registry.register<WatchPartyPort>(displaySystem)
        registry.register<MediaResolverRegistry>(resolverChain)
        registry.register<MediaSearchService>(YtDlpSearchService())
        registry.register<StreamSelector>(DefaultStreamSelector())
        registry.register<OverlayManager>(PipOverlayManager)
        registry.register<CrosshairPolicy>(CrosshairPolicy { ClientStateManager.isOnScreen })
        registry.register<DisplayInteractionService>(MinecraftDisplayInteractionService)
        registry.register<KeyBindingRegistry>(DefaultKeyBindingRegistry().apply { register(DisplayMenuInputHandler.OPEN_MENU_BINDING) })
        registry.register<InputHandler>(CompositeInputHandler().apply { register(DisplayMenuInputHandler()) })
        registry.register<ClientRenderService>(ScreenRenderer)
        registry.register<PopoutManager>(DefaultPopoutManager())
        registry.register<DisplayService>(displayService)
        registry.register<PlaybackService>(playbackService)
        registry.register<MediaSessionManager>(DefaultMediaSessionManager(playbackService, displayService))
        registry.register<WatchPartyService>(DefaultWatchPartyService(displaySystem))
        registry.register<ClientCapabilityDetector>(MinecraftClientCapabilityDetector)
        registry.register<CapabilityNegotiationService>(
            DefaultCapabilityNegotiationService(
                MinecraftClientCapabilityDetector
            )
        )
        registry.register<DisplayRenderer>(DefaultDisplayRenderer())
        registry.register<TextureUploaderFactory>(TextureUploaderFactory { AsyncTextureUploader(stateCache = it) })
        registry.register<RenderHook>(RenderHook { context ->
            registry.getOrNull<DisplayRenderer>()?.takeIf { it.registeredCount > 0 }?.renderAll(context)
        })
    }
}
